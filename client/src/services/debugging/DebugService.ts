import * as net from "net";
import type { Disposable } from "vscode";
import * as vscode from "vscode";
import type { ProjectInfo } from "../../features/models/modelTypes";
import { ProjectType } from "../../features/models/modelTypes";
import type { ErrorService } from "../errors/ErrorService";
import { ErrorSeverity, ErrorSource } from "../errors/errorTypes";
import type { GradleService } from "../gradle/GradleService";
import type { ConfigurationService } from "../workspace/ConfigurationService";

export interface DebugOptions {
  port?: number | undefined;
  cancellationToken?: vscode.CancellationToken | undefined;
  timeoutMs?: number | undefined;
}

export class DebugService implements Disposable {
  private activeDebugSession: vscode.DebugSession | null = null;
  private readonly disposables: vscode.Disposable[] = [];
  private currentProjectPath: string | null = null;
  private disposed = false;

  constructor(
    private readonly gradleService: GradleService,
    private readonly errorService: ErrorService,
    private readonly configService?: ConfigurationService
  ) {
    this.disposables.push(
      vscode.debug.onDidTerminateDebugSession(session => {
        if (this.activeDebugSession?.id === session.id) {
          this.activeDebugSession = null;
          if (this.currentProjectPath) {
            void this.gradleService.cancelTask(this.currentProjectPath, "bootRun");
            this.currentProjectPath = null;
          }
        }
      })
    );
  }

  async debugGrailsApp(
    projectInfo: ProjectInfo,
    options: DebugOptions = {}
  ): Promise<boolean> {
    if (this.disposed) return false;

    // 1. Check workspace trust
    if (vscode.workspace && vscode.workspace.isTrusted === false) {
      const msg =
        "Execution blocked: Workspace is untrusted. Debugging requires workspace trust.";
      this.errorService.handleError(
        msg,
        null,
        ErrorSource.Extension,
        ErrorSeverity.Warning
      );
      vscode.window.showWarningMessage(msg);
      return false;
    }

    // 2. Validate project type
    if (projectInfo.type !== ProjectType.Grails) {
      const msg = "Debug application is only supported for Grails applications.";
      this.errorService.handleError(
        msg,
        null,
        ErrorSource.Extension,
        ErrorSeverity.Warning
      );
      vscode.window.showWarningMessage(msg);
      return false;
    }

    // 3. Check Java debugger extension availability
    const javaDebugExt =
      vscode.extensions.getExtension("vscjava.vscode-java-debug") ??
      vscode.extensions.getExtension("redhat.java");
    if (!javaDebugExt) {
      const msg =
        "Java debugger extension is not installed. Please install 'Debugger for Java' (vscjava.vscode-java-debug) to enable JVM debugging.";
      this.errorService.handleError(
        msg,
        null,
        ErrorSource.Extension,
        ErrorSeverity.Error
      );
      vscode.window.showErrorMessage(msg);
      return false;
    }

    // 4. Resolve debug port and check for conflict
    const debugPort =
      options.port ??
      this.configService?.get<number>("debug.port", 5005) ??
      5005;

    const isAvailable = await this.gradleService.checkPortAvailable(debugPort);
    if (!isAvailable) {
      const msg = `Debug port ${debugPort} is already in use by another process. Please terminate the conflicting process or configure a different debug port.`;
      this.errorService.handleError(
        msg,
        null,
        ErrorSource.GradleService,
        ErrorSeverity.Error
      );
      vscode.window.showErrorMessage(msg);
      return false;
    }

    vscode.window.showInformationMessage(
      `Starting Grails application in debug mode on port ${debugPort}...`
    );

    this.currentProjectPath = projectInfo.rootPath;

    // 5. Start bootRun with JVM debugging
    const taskPromise = this.gradleService.executeGradleTask(projectInfo, "bootRun", {
      args: ["--debug-jvm"],
      cancellationToken: options.cancellationToken,
    });

    taskPromise.catch(err => {
      this.errorService.handleError(
        "bootRun debug task failed",
        err,
        ErrorSource.GradleService,
        ErrorSeverity.Error
      );
    });

    // 6. Dynamically wait for the JVM process to bind to debug port
    const timeout = options.timeoutMs ?? 30000;
    const bound = await this.waitForPortOpen(
      "localhost",
      debugPort,
      timeout,
      options.cancellationToken
    );

    if (!bound) {
      if (options.cancellationToken?.isCancellationRequested) {
        vscode.window.showInformationMessage("Debug launch was cancelled.");
      } else {
        vscode.window.showErrorMessage(
          `Timed out waiting for Grails application to open debug port ${debugPort}.`
        );
      }
      await this.gradleService.cancelTask(projectInfo.rootPath, "bootRun");
      this.currentProjectPath = null;
      return false;
    }

    // 7. Attach VS Code's Java debugger
    try {
      const workspaceFolder =
        vscode.workspace.getWorkspaceFolder(vscode.Uri.file(projectInfo.rootPath)) ??
        vscode.workspace.workspaceFolders?.[0];

      const success = await vscode.debug.startDebugging(workspaceFolder, {
        type: "java",
        name: `Debug Grails (${projectInfo.name})`,
        request: "attach",
        hostName: "localhost",
        port: debugPort,
      });

      if (success) {
        this.activeDebugSession = vscode.debug.activeDebugSession ?? null;
        vscode.window.showInformationMessage(
          "Debugger successfully attached to Grails application."
        );
      } else {
        vscode.window.showErrorMessage(
          "Failed to attach Java debugger. Ensure 'Language Support for Java' extension is activated."
        );
        await this.gradleService.cancelTask(projectInfo.rootPath, "bootRun");
        this.currentProjectPath = null;
      }
      return success;
    } catch (error) {
      vscode.window.showErrorMessage(`Failed to start debugging: ${String(error)}`);
      await this.gradleService.cancelTask(projectInfo.rootPath, "bootRun");
      this.currentProjectPath = null;
      return false;
    }
  }

  /**
   * Polls a TCP port until a socket successfully connects or timeout/cancellation occurs.
   */
  async waitForPortOpen(
    host: string,
    port: number,
    timeoutMs: number,
    token?: vscode.CancellationToken
  ): Promise<boolean> {
    const start = Date.now();

    while (Date.now() - start < timeoutMs) {
      if (token?.isCancellationRequested) {
        return false;
      }

      const connected = await new Promise<boolean>(resolve => {
        const socket = new net.Socket();
        socket.setTimeout(400);

        socket.on("connect", () => {
          socket.destroy();
          resolve(true);
        });

        socket.on("timeout", () => {
          socket.destroy();
          resolve(false);
        });

        socket.on("error", () => {
          socket.destroy();
          resolve(false);
        });

        socket.connect(port, host);
      });

      if (connected) {
        return true;
      }

      await new Promise(resolve => setTimeout(resolve, 300));
    }

    return false;
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    if (this.currentProjectPath) {
      void this.gradleService.cancelTask(this.currentProjectPath, "bootRun");
      this.currentProjectPath = null;
    }
    this.activeDebugSession = null;
    this.disposables.forEach(d => {
      d.dispose();
    });
    this.disposables.length = 0;
  }
}
