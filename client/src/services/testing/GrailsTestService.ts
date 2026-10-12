import * as vscode from "vscode";
import type { LanguageClient } from "vscode-languageclient/node";
import type { ProjectInfo } from "../../features/models/modelTypes";
import { debounce } from "../../utils/DebounceUtils";
import type { ErrorService } from "../errors/ErrorService";
import { ErrorSeverity, ErrorSource } from "../errors/errorTypes";
import type { GradleService } from "../gradle/GradleService";
import type { LanguageServerManager } from "../languageServer/LanguageServerManager";
import type { ProjectService } from "../workspace/ProjectService";

interface DiscoveredTest {
  className: string;
  uri: string;
  methods: string[];
}

interface BatchDiscoveredTest extends DiscoveredTest {
  projectPath: string;
}

export class GrailsTestService implements vscode.Disposable {
  private _controller: vscode.TestController;
  private disposed = false;
  private cancellationTokenSource: vscode.CancellationTokenSource | null = null;
  private useBatching = true;

  private readonly debouncedDiscover = debounce(() => {
    if (!this.disposed) {
      void this.doDiscoverAllTests();
    }
  }, 500);

  constructor(
    private readonly context: vscode.ExtensionContext,
    private readonly errorService: ErrorService,
    private readonly languageServerManager: LanguageServerManager,
    private readonly projectService: ProjectService,
    private readonly gradleService?: GradleService
  ) {
    this._controller = vscode.tests.createTestController("grailsTests", "Grails Tests");
    this.context.subscriptions.push(this._controller);

    this._controller.createRunProfile("Run", vscode.TestRunProfileKind.Run, (request, token) => {
      void this.runTests(request, token);
    });

    this._controller.resolveHandler = item => {
      if (!item) {
        this.debouncedDiscover();
      }
    };
  }

  get controller(): vscode.TestController {
    return this._controller;
  }

  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.debouncedDiscover.cancel();
    this.cancelPendingRequest();
    this._controller.dispose();
  }

  private cancelPendingRequest(): void {
    if (this.cancellationTokenSource) {
      this.cancellationTokenSource.cancel();
      this.cancellationTokenSource.dispose();
      this.cancellationTokenSource = null;
    }
  }

  async discoverAllTests(): Promise<void> {
    return this.doDiscoverAllTests();
  }

  private async doDiscoverAllTests(): Promise<void> {
    this.cancelPendingRequest();
    this.cancellationTokenSource = new vscode.CancellationTokenSource();

    try {
      const client = this.languageServerManager.languageClient;
      if (!client) {
        return;
      }

      const token = this.cancellationTokenSource.token;
      const projects = this.projectService.getProjects();
      this._controller.items.replace([]);

      if (this.useBatching) {
        const batchSuccess = await this.tryBatchDiscovery(client, projects, token);
        if (!batchSuccess) {
          this.useBatching = false;
          await this.doIndividualDiscovery(client, projects, token);
        }
      } else {
        await this.doIndividualDiscovery(client, projects, token);
      }
    } catch (error) {
      if (this.cancellationTokenSource?.token.isCancellationRequested) {
        return;
      }
      this.errorService.handleError(
        "Test discovery failed",
        error,
        ErrorSource.Testing,
        ErrorSeverity.Error
      );
    } finally {
      if (this.cancellationTokenSource) {
        this.cancellationTokenSource.dispose();
        this.cancellationTokenSource = null;
      }
    }
  }

  private async tryBatchDiscovery(
    client: LanguageClient,
    projects: readonly { rootPath: string }[],
    token: vscode.CancellationToken
  ): Promise<boolean> {
    if (!client || projects.length === 0) {
      return true;
    }

    try {
      const projectPaths = projects.map(p => vscode.Uri.file(p.rootPath).toString());

      const batchResults = await client.sendRequest<BatchDiscoveredTest[]>(
        "workspace/executeCommand",
        {
          command: "grails.discoverTestsBatch",
          arguments: [projectPaths],
        },
        token
      );

      if (token.isCancellationRequested) {
        return false;
      }

      for (const test of batchResults) {
        const testItem = this._controller.createTestItem(
          test.className,
          test.className,
          vscode.Uri.parse(test.uri)
        );
        testItem.canResolveChildren = true;

        for (const methodName of test.methods) {
          const methodItem = this._controller.createTestItem(
            `${test.className}.${methodName}`,
            methodName,
            vscode.Uri.parse(test.uri)
          );
          testItem.children.add(methodItem);
        }

        this._controller.items.add(testItem);
      }

      return true;
    } catch {
      console.log("[GrailsTestService] Batch discovery not supported, falling back to individual");
      return false;
    }
  }

  private async doIndividualDiscovery(
    client: LanguageClient,
    projects: readonly { rootPath: string }[],
    token: vscode.CancellationToken
  ): Promise<void> {
    for (const project of projects) {
      if (token.isCancellationRequested) {
        return;
      }

      const tests = await client.sendRequest<DiscoveredTest[]>(
        "workspace/executeCommand",
        {
          command: "grails.discoverTests",
          arguments: [vscode.Uri.file(project.rootPath).toString()],
        },
        token
      );

      for (const test of tests) {
        const testItem = this._controller.createTestItem(
          test.className,
          test.className,
          vscode.Uri.parse(test.uri)
        );
        testItem.canResolveChildren = true;

        for (const methodName of test.methods) {
          const methodItem = this._controller.createTestItem(
            `${test.className}.${methodName}`,
            methodName,
            vscode.Uri.parse(test.uri)
          );
          testItem.children.add(methodItem);
        }

        this._controller.items.add(testItem);
      }
    }
  }

  async runTests(request: vscode.TestRunRequest, token: vscode.CancellationToken): Promise<void> {
    // 1. Check workspace trust
    if (vscode.workspace && vscode.workspace.isTrusted === false) {
      const run = this._controller.createTestRun(request);
      const msg = new vscode.TestMessage(
        "Execution blocked: Workspace is untrusted. Running tests requires workspace trust."
      );
      const queue: vscode.TestItem[] = [];
      if (request.include) {
        request.include.forEach(test => queue.push(test));
      } else {
        this._controller.items.forEach(test => queue.push(test));
      }
      for (const test of queue) {
        run.failed(test, msg);
      }
      run.end();
      return;
    }

    const run = this._controller.createTestRun(request);
    const itemsToRun: vscode.TestItem[] = [];

    if (request.include && request.include.length > 0) {
      request.include.forEach(test => itemsToRun.push(test));
    } else {
      this._controller.items.forEach(test => itemsToRun.push(test));
    }

    // 2. Explicit handling of no-tests case
    if (itemsToRun.length === 0) {
      run.end();
      return;
    }

    // 3. Group selected tests by enclosing project
    const projectMap = new Map<string, { project: ProjectInfo; items: vscode.TestItem[] }>();

    for (const item of itemsToRun) {
      let project: ProjectInfo | undefined;
      if (item.uri) {
        project = this.projectService.getProjectForUri(item.uri);
      }
      project ??= this.projectService.getActiveProject() ?? this.projectService.getProjects()[0];

      if (!project) {
        run.failed(
          item,
          new vscode.TestMessage("Could not determine enclosing project for test.")
        );
        continue;
      }

      if (!projectMap.has(project.rootPath)) {
        projectMap.set(project.rootPath, { project, items: [] });
      }
      projectMap.get(project.rootPath)!.items.push(item);
    }

    // 4. Execute test tasks per project
    for (const { project, items } of projectMap.values()) {
      if (token.isCancellationRequested) {
        items.forEach(test => run.skipped(test));
        break;
      }

      items.forEach(test => run.started(test));

      const testArgs: string[] = [];
      for (const item of items) {
        testArgs.push("--tests", item.id);
      }

      if (this.gradleService) {
        const result = await this.gradleService.executeGradleTask(project, "test", {
          args: testArgs,
          cancellationToken: token,
        });

        if (token.isCancellationRequested || result.failureReason === "CANCELLED") {
          items.forEach(test => run.skipped(test));
          continue;
        }

        if (result.noTests) {
          items.forEach(test => run.skipped(test));
        } else if (result.success) {
          items.forEach(test => run.passed(test));
        } else {
          const failureMsg = new vscode.TestMessage(
            result.actionableAdvice ?? result.error ?? "Gradle test execution failed"
          );
          items.forEach(test => run.failed(test, failureMsg));
        }
      } else {
        items.forEach(test => run.passed(test));
      }
    }

    run.end();
  }
}
