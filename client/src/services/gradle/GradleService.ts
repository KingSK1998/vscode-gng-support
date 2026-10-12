import * as childProcess from "child_process";
import * as fs from "fs";
import * as net from "net";
import * as path from "path";
import type { Disposable, Task } from "vscode";
import { extensions, ProgressLocation, window, workspace } from "vscode";
import type { ProjectInfo } from "../../features/models/modelTypes";
import { ProjectType } from "../../features/models/modelTypes";
import type { ErrorService } from "../errors/ErrorService";
import { ErrorSeverity, ErrorSource } from "../errors/errorTypes";
import type { ConfigurationService } from "../workspace/ConfigurationService";
import type { StatusBarService } from "../workspace/StatusBarService";
import type {
  Api,
  TaskExecutionOptions,
  TaskExecutionResult,
} from "./gradleTypes";
import { GrailsTask } from "./gradleTypes";

interface RunningTask {
  process: childProcess.ChildProcess;
  taskName: string;
  projectPath: string;
}

/**
 * Grails-focused Gradle service that handles task execution, environment validation,
 * wrapper resolution, port conflict detection, and process lifecycle.
 */
export class GradleService implements Disposable {
  private static readonly GRADLE_EXTENSION_ID = "vscjava.vscode-gradle";
  private gradleApi: Api | undefined;
  private isInitialized = false;
  private intializationPromise?: Promise<boolean>;
  private readonly runningProcesses = new Map<string, RunningTask>();
  private disposed = false;

  constructor(
    private readonly statusBarService: StatusBarService,
    private readonly errorService: ErrorService,
    private readonly configService?: ConfigurationService
  ) {}

  /* ================= CORE SYNC FUNCTIONALITY =================== */

  /**
   * Fast sync - returns immediately if already initialized.
   * Otherwise, starts initialization but does not wait for it to complete.
   */
  quickSync(): boolean {
    if (this.isInitialized) {
      return true; // Already synced
    }

    // Start initialization in background if not already started
    this.intializationPromise ??= this.fullSync();

    return false; // Not ready yet, but initializing
  }

  /**
   * Full blocking sync - only calls when actually needed.
   */
  async fullSync(): Promise<boolean> {
    if (this.isInitialized) {
      return true; // Already synced
    }

    try {
      this.statusBarService.sync("🔄 Initializing Gradle...");

      // Add timeout wrapper
      const syncResult = await Promise.race([
        this.performGradleSync(),
        this.createTimeoutPromise(15000), // 15 sec timeout
      ]);

      if (syncResult) {
        this.isInitialized = true;
        this.statusBarService.success("✅ Gradle ready");
        return true;
      } else {
        this.statusBarService.warning("⚠️ Gradle initialization timed out");
        return false;
      }
    } catch (error) {
      this.errorService.handleError(
        "Gradle sync failed",
        error,
        ErrorSource.GradleService,
        ErrorSeverity.Warning
      );
      this.statusBarService.warning("⚠️ Gradle unavailable - limited features");
      return false;
    }
  }

  private async performGradleSync(): Promise<boolean> {
    const extension = extensions.getExtension(GradleService.GRADLE_EXTENSION_ID);
    if (!extension) {
      return false;
    }

    if (!extension.isActive) {
      await extension.activate();
    }

    this.gradleApi = extension.exports as Api;
    return !!this.gradleApi;
  }

  private createTimeoutPromise(timeoutMs: number): Promise<boolean> {
    return new Promise<boolean>(resolve => {
      setTimeout(() => resolve(false), timeoutMs);
    });
  }

  /**
   * Non-blocking sync - starts initialization in background and returns immediately.
   */
  sync(): void {
    if (this.isInitialized && this.gradleApi) {
      return;
    }

    if (this.intializationPromise) {
      return;
    }

    this.intializationPromise ??= this.doSync().catch(error => {
      this.errorService.handleError(
        "Gradle sync failed",
        error,
        ErrorSource.GradleService,
        ErrorSeverity.Warning
      );
      return false;
    });
  }

  /**
   * Wait for Gradle sync to complete.
   */
  async waitForSync(): Promise<boolean> {
    if (this.isInitialized && this.gradleApi) {
      return true;
    }

    this.intializationPromise ??= this.doSync().catch(error => {
      this.errorService.handleError(
        "Gradle sync failed",
        error,
        ErrorSource.GradleService,
        ErrorSeverity.Warning
      );
      return false;
    });

    return this.intializationPromise;
  }

  private async doSync(): Promise<boolean> {
    try {
      this.statusBarService.sync("🔄 Initializing Gradle...");

      const extension = extensions.getExtension(GradleService.GRADLE_EXTENSION_ID);
      if (!extension) {
        return false;
      }

      if (!extension.isActive) {
        this.statusBarService.sync("Activating Gradle extension...");
        await extension.activate();
      }

      this.gradleApi = extension.exports as Api;
      if (!this.gradleApi) {
        return false;
      }

      this.statusBarService.sync("Syncing with Gradle projects...");

      const syncSuccess = await window.withProgress(
        {
          location: ProgressLocation.Notification,
          title: "Synchronizing Gradle projects",
          cancellable: false,
        },
        () => this.waitForTaskProviderReady()
      );

      if (syncSuccess) {
        this.isInitialized = true;
        this.statusBarService.success("✅ Gradle ready");
        return true;
      } else {
        return false;
      }
    } catch (error) {
      this.errorService.handleError(
        "Failed to sync with Gradle",
        error,
        ErrorSource.GradleService,
        ErrorSeverity.Error
      );
      return false;
    }
  }

  private async waitForTaskProviderReady(): Promise<boolean> {
    const provider = this.gradleApi?.getTaskProvider?.();
    if (!provider || typeof provider.onDidLoadTasks !== "function") {
      return false;
    }

    const timeoutPromise = new Promise((_, reject) => {
      setTimeout(() => {
        reject(new Error("Timeout waiting for task provider to be ready"));
      }, 30000);
    });

    const readyPromise = new Promise(resolve => {
      const disposable = provider.onDidLoadTasks((tasks: Task[]) => {
        console.log(`[GradleService] Tasks loaded: ${tasks.length} tasks found`);
        disposable.dispose();
        resolve(true);
      });
    });

    await provider.provideTasks();
    const result = await Promise.race([readyPromise, timeoutPromise]);
    return result as boolean;
  }

  /* ================= ENVIRONMENT & RESOLUTION =================== */

  /**
   * Resolves the Java executable path and validates presence.
   */
  resolveJavaExecutable(): { executable: string | null; error?: string; advice?: string } {
    let javaHome = this.configService?.javaHome;
    if (!javaHome) {
      const config = workspace.getConfiguration("grails");
      javaHome = config?.get<string>("javaHome");
    }
    javaHome ??= process.env.JAVA_HOME;

    if (javaHome && fs.existsSync(javaHome)) {
      const binaryName = process.platform === "win32" ? "java.exe" : "java";
      const candidate = path.join(javaHome, "bin", binaryName);
      if (fs.existsSync(candidate)) {
        return { executable: candidate };
      }
    }

    // Check system PATH
    const pathDirs = (process.env.PATH ?? "").split(path.delimiter);
    const binaryName = process.platform === "win32" ? "java.exe" : "java";
    for (const dir of pathDirs) {
      const candidate = path.join(dir, binaryName);
      if (fs.existsSync(candidate)) {
        return { executable: candidate };
      }
    }

    return {
      executable: null,
      error: "Java executable not found",
      advice:
        "Grails 7+ requires JDK 17+. Please set 'grails.javaHome' in VS Code settings or ensure JAVA_HOME is configured in your system environment.",
    };
  }

  /**
   * Resolves the Gradle executable or wrapper in the target project.
   */
  resolveGradleExecutable(projectPath: string): {
    executable: string | null;
    isWrapper: boolean;
    error?: string;
    advice?: string;
  } {
    if (!projectPath || !fs.existsSync(projectPath)) {
      return {
        executable: null,
        isWrapper: false,
        error: `Project directory does not exist: ${projectPath}`,
        advice: "Ensure the project folder exists before running Gradle tasks.",
      };
    }

    if (process.platform === "win32") {
      const candidates = ["gradlew.bat", "gradlew.cmd", "gradlew"];
      for (const candidate of candidates) {
        const fullPath = path.join(projectPath, candidate);
        if (fs.existsSync(fullPath)) {
          return { executable: fullPath, isWrapper: true };
        }
      }
    } else {
      const wrapperPath = path.join(projectPath, "gradlew");
      if (fs.existsSync(wrapperPath)) {
        return { executable: wrapperPath, isWrapper: true };
      }
    }

    // Check system gradle
    const pathDirs = (process.env.PATH ?? "").split(path.delimiter);
    const binaryName = process.platform === "win32" ? "gradle.bat" : "gradle";
    for (const dir of pathDirs) {
      const candidate = path.join(dir, binaryName);
      if (fs.existsSync(candidate)) {
        return { executable: candidate, isWrapper: false };
      }
    }

    return {
      executable: null,
      isWrapper: false,
      error: "Gradle wrapper not found",
      advice: `No 'gradlew' wrapper script found in '${projectPath}' and no Gradle installation found on PATH. Run 'gradle wrapper' to generate it.`,
    };
  }

  /**
   * Checks if a network port is available for binding.
   */
  async checkPortAvailable(port: number): Promise<boolean> {
    return new Promise(resolve => {
      const server = net.createServer();
      server.unref();

      server.on("error", () => {
        resolve(false);
      });

      server.listen({ port, host: "127.0.0.1" }, () => {
        server.close(() => {
          resolve(true);
        });
      });
    });
  }

  /**
   * Detects port conflict indicators from output strings.
   */
  detectPortConflict(output: string): number | null {
    const patterns = [
      /Port (\d+) was already in use/i,
      /Web server failed to start\. Port (\d+) was already in use/i,
      /Address already in use:\s*(\d+)/i,
      /Address already in use.*?:(\d+)/i,
      /ERROR: Cannot listen on port (\d+)/i,
      /Address already in use.*?: bind/i,
      /transport error 202: bind\(\) failed: Address already in use/i,
      /Address already in use/i,
    ];

    for (const pattern of patterns) {
      const match = output.match(pattern);
      if (match) {
        if (match[1]) {
          return parseInt(match[1], 10);
        }
        return 8080; // default Grails port
      }
    }

    return null;
  }

  /* ================= PROCESS MANAGEMENT ========================= */

  private getProcessKey(projectPath: string, taskName: string): string {
    return `${path.normalize(projectPath).toLowerCase()}::${taskName}`;
  }

  /**
   * Cancels/terminates any running process for the given project and task.
   */
  cancelTask(projectPath: string, taskName: string): Promise<boolean> {
    const key = this.getProcessKey(projectPath, taskName);
    const running = this.runningProcesses.get(key);
    if (!running) {
      return Promise.resolve(false);
    }

    try {
      running.process.kill("SIGTERM");
      setTimeout(() => {
        if (!running.process.killed) {
          running.process.kill("SIGKILL");
        }
      }, 1500);
      this.runningProcesses.delete(key);
      return Promise.resolve(true);
    } catch {
      return Promise.resolve(false);
    }
  }

  /**
   * Returns whether a task is currently executing for the project.
   */
  isTaskRunning(projectPath: string, taskName: string): boolean {
    const key = this.getProcessKey(projectPath, taskName);
    return this.runningProcesses.has(key);
  }

  /* ================= NATIVE TASK EXECUTION ====================== */

  /**
   * Executes a Gradle task with full verification of workspace trust, environment,
   * wrapper presence, cancellation, port conflicts, and process tracking.
   */
  async executeGradleTask(
    projectInfo: ProjectInfo,
    taskName: string,
    options: TaskExecutionOptions = {}
  ): Promise<TaskExecutionResult> {
    if (this.disposed) {
      return {
        success: false,
        failureReason: "EXECUTION_FAILED",
        error: "GradleService has been disposed",
      };
    }

    // 1. Check workspace trust
    if (workspace && workspace.isTrusted === false) {
      const errorMsg =
        "Execution blocked: Workspace is untrusted. Running Gradle tasks requires workspace trust.";
      this.errorService.handleError(
        errorMsg,
        null,
        ErrorSource.GradleService,
        ErrorSeverity.Warning
      );
      return {
        success: false,
        failureReason: "WORKSPACE_UNTRUSTED",
        error: errorMsg,
        actionableAdvice:
          "Grant workspace trust in VS Code to execute Gradle tasks, run applications, or run tests.",
      };
    }

    // 2. Validate Java environment
    const javaResult = this.resolveJavaExecutable();
    if (!javaResult.executable) {
      this.errorService.handleError(
        javaResult.error ?? "Java missing",
        null,
        ErrorSource.GradleService,
        ErrorSeverity.Error
      );
      return {
        success: false,
        failureReason: "JAVA_MISSING",
        error: javaResult.error,
        actionableAdvice: javaResult.advice,
      };
    }

    // 3. Resolve Gradle wrapper / executable
    const gradleResult = this.resolveGradleExecutable(projectInfo.rootPath);
    if (!gradleResult.executable) {
      this.errorService.handleError(
        gradleResult.error ?? "Gradle missing",
        null,
        ErrorSource.GradleService,
        ErrorSeverity.Error
      );
      return {
        success: false,
        failureReason: "WRAPPER_MISSING",
        error: gradleResult.error,
        actionableAdvice: gradleResult.advice,
      };
    }

    // 4. Pre-flight port conflict check (if requested)
    if (options.portCheck) {
      const isAvailable = await this.checkPortAvailable(options.portCheck);
      if (!isAvailable) {
        const portMsg = `Port ${options.portCheck} is already in use by another process.`;
        this.errorService.handleError(portMsg, null, ErrorSource.GradleService, ErrorSeverity.Error);
        return {
          success: false,
          failureReason: "PORT_CONFLICT",
          error: portMsg,
          actionableAdvice: `Terminate the process listening on port ${options.portCheck} or configure a different port before launching.`,
        };
      }
    }

    // 5. Restart handling: terminate any existing instance of this task
    await this.cancelTask(projectInfo.rootPath, taskName);

    // 6. Spawn process
    const processKey = this.getProcessKey(projectInfo.rootPath, taskName);
    const args = [taskName, ...(options.args ?? [])];
    const env: NodeJS.ProcessEnv = {
      ...process.env,
      ...(options.env ?? {}),
    };
    if (javaResult.executable) {
      const javaBinDir = path.dirname(javaResult.executable);
      const javaHomeDir = path.dirname(javaBinDir);
      env.JAVA_HOME = javaHomeDir;
      env.PATH = `${javaBinDir}${path.delimiter}${env.PATH ?? ""}`;
    }

    this.statusBarService.sync(`Running Gradle: ${taskName}...`);

    return new Promise<TaskExecutionResult>(resolve => {
      let stderrBuffer = "";
      let combinedBuffer = "";
      let cancelled = false;

      // Handle cancellation token
      if (options.cancellationToken) {
        options.cancellationToken.onCancellationRequested(() => {
          cancelled = true;
          void this.cancelTask(projectInfo.rootPath, taskName);
        });
      }

      const child = childProcess.spawn(gradleResult.executable!, args, {
        cwd: projectInfo.rootPath,
        env,
        shell: process.platform === "win32",
      });

      this.runningProcesses.set(processKey, {
        process: child,
        taskName,
        projectPath: projectInfo.rootPath,
      });

      child.stdout?.on("data", (data: Buffer | string) => {
        const chunk = data.toString();
        combinedBuffer += chunk;
        if (options.onOutput) {
          options.onOutput(chunk);
        }
      });

      child.stderr?.on("data", (data: Buffer | string) => {
        const chunk = data.toString();
        stderrBuffer += chunk;
        combinedBuffer += chunk;
        if (options.onOutput) {
          options.onOutput(chunk);
        }
      });

      child.on("error", err => {
        this.runningProcesses.delete(processKey);
        this.statusBarService.warning(`Task ${taskName} failed to start`);
        resolve({
          success: false,
          failureReason: "EXECUTION_FAILED",
          error: `Failed to spawn Gradle process: ${err.message}`,
          actionableAdvice: `Check permissions on '${gradleResult.executable}' and verify system path settings.`,
          output: combinedBuffer,
        });
      });

      child.on("close", (exitCode, signal) => {
        this.runningProcesses.delete(processKey);

        if (cancelled || signal === "SIGTERM" || signal === "SIGKILL") {
          this.statusBarService.ready(`Task ${taskName} cancelled`);
          resolve({
            success: false,
            failureReason: "CANCELLED",
            error: `Task ${taskName} was cancelled by user.`,
            output: combinedBuffer,
          });
          return;
        }

        // Check for port conflicts in output
        const conflictedPort = this.detectPortConflict(combinedBuffer);
        if (conflictedPort !== null && exitCode !== 0) {
          this.statusBarService.warning(`Port ${conflictedPort} conflict detected`);
          resolve({
            success: false,
            exitCode: exitCode ?? -1,
            failureReason: "PORT_CONFLICT",
            error: `Port ${conflictedPort} is already in use by another process.`,
            actionableAdvice: `Stop the existing application running on port ${conflictedPort} or configure a different port.`,
            output: combinedBuffer,
          });
          return;
        }

        // Test outcome parsing
        const testSummary = this.parseTestSummary(combinedBuffer);

        if (exitCode === 0) {
          this.statusBarService.success(`✅ ${taskName} completed`);
          resolve({
            success: true,
            exitCode: 0,
            output: combinedBuffer,
            ...testSummary,
          });
        } else {
          this.statusBarService.warning(`⚠️ ${taskName} exited with code ${exitCode}`);
          resolve({
            success: false,
            exitCode: exitCode ?? -1,
            failureReason: "EXECUTION_FAILED",
            error: `Task ${taskName} failed with exit code ${exitCode}.`,
            actionableAdvice:
              stderrBuffer.trim() ||
              "Inspect output channel for detailed task compilation and execution logs.",
            output: combinedBuffer,
            ...testSummary,
          });
        }
      });
    });
  }

  private parseTestSummary(output: string): {
    noTests?: boolean;
    testsPassed?: number;
    testsFailed?: number;
    testsSkipped?: number;
  } {
    if (output.includes("NO-SOURCE") || output.includes("0 tests completed")) {
      return { noTests: true, testsPassed: 0, testsFailed: 0, testsSkipped: 0 };
    }

    // Pattern: "X tests completed, Y failed, Z skipped"
    const summaryMatch = output.match(/(\d+)\s+tests completed(?:,\s*(\d+)\s+failed)?(?:,\s*(\d+)\s+skipped)?/i);
    if (summaryMatch) {
      const completed = parseInt(summaryMatch[1], 10);
      const failed = summaryMatch[2] ? parseInt(summaryMatch[2], 10) : 0;
      const skipped = summaryMatch[3] ? parseInt(summaryMatch[3], 10) : 0;
      const passed = Math.max(0, completed - failed - skipped);
      return {
        noTests: completed === 0,
        testsPassed: passed,
        testsFailed: failed,
        testsSkipped: skipped,
      };
    }

    return {};
  }

  /* ================= TASK EXECUTION WRAPPER ===================== */

  /**
   * Run any Gradle task using the native execution engine, or delegating to vscode-gradle if available.
   */
  async runTask(
    projectInfo: ProjectInfo,
    taskName: GrailsTask | string,
    args: string[] = [],
    onOutputProxy?: (message: string) => void
  ): Promise<boolean> {
    const result = await this.executeGradleTask(projectInfo, taskName, {
      args,
      onOutput: onOutputProxy,
    });
    return result.success;
  }

  /* ================= GRAILS TASK SHORTCUTS ========================= */

  async runGrailsApp(projectInfo: ProjectInfo): Promise<boolean> {
    if (projectInfo.type !== ProjectType.Grails) {
      this.errorService.handleError(
        "bootRun is only available for Grails projects",
        null,
        ErrorSource.GradleService,
        ErrorSeverity.Warning
      );
      return false;
    }
    const defaultPort = this.configService?.serverPort ?? 8080;
    const result = await this.executeGradleTask(projectInfo, GrailsTask.BootRun, {
      portCheck: defaultPort,
    });
    return result.success;
  }

  async testGrailsApp(projectInfo: ProjectInfo): Promise<boolean> {
    const result = await this.executeGradleTask(projectInfo, GrailsTask.Test);
    return result.success;
  }

  async buildProject(
    projectInfo: ProjectInfo,
    taskName: string = GrailsTask.Build
  ): Promise<boolean> {
    const result = await this.executeGradleTask(projectInfo, taskName);
    return result.success;
  }

  async cleanProject(projectInfo: ProjectInfo): Promise<boolean> {
    const result = await this.executeGradleTask(projectInfo, GrailsTask.Clean);
    return result.success;
  }

  /* ================= UTILITIES =================================== */

  hasGrailsTasks(projectInfo: ProjectInfo): boolean {
    return projectInfo.type === ProjectType.Grails || projectInfo.type === ProjectType.GrailsPlugin;
  }

  readonly isReady = true;

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    for (const [key, running] of this.runningProcesses.entries()) {
      try {
        running.process.kill("SIGTERM");
      } catch {
        // ignore
      }
      this.runningProcesses.delete(key);
    }
    this.gradleApi = undefined;
    this.isInitialized = false;
  }
}
