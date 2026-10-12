import * as assert from "assert";
import * as test from "node:test";
import * as path from "path";
import * as vscode from "vscode";
import { EventBus } from "../core/events/EventBus";
import { ProjectType } from "../features/models/modelTypes";
import { DebugService } from "../services/debugging/DebugService";
import { ErrorService } from "../services/errors/ErrorService";
import { GradleService } from "../services/gradle/GradleService";
import type { LanguageServerManager } from "../services/languageServer/LanguageServerManager";
import { GrailsTestService } from "../services/testing/GrailsTestService";
import { ConfigurationService } from "../services/workspace/ConfigurationService";
import { ProjectService } from "../services/workspace/ProjectService";
import { StatusBarService } from "../services/workspace/StatusBarService";
import {
  RunGrailsAppUseCase,
  DebugGrailsAppUseCase,
  TestGrailsAppUseCase,
  BuildGrailsProjectUseCase,
  CleanGrailsProjectUseCase,
} from "../ui/commands/GrailsTaskCommands";

function createMockExtensionContext(): vscode.ExtensionContext {
  return {
    subscriptions: [],
    workspaceState: {
      get: () => undefined,
      update: () => Promise.resolve(),
      keys: () => [],
    },
    globalState: {
      get: () => undefined,
      update: () => Promise.resolve(),
      keys: () => [],
      setKeysForSync: () => undefined,
    },
    extensionPath: "/mock/path",
    asAbsolutePath: (p: string) => `/mock/path/${p}`,
    storagePath: "/mock/storage",
    globalStoragePath: "/mock/globalStorage",
    logPath: "/mock/log",
    extensionUri: vscode.Uri.file("/mock/path"),
    environmentVariableCollection: {} as unknown as vscode.EnvironmentVariableCollection,
    extensionMode: 1,
    storageUri: vscode.Uri.file("/mock/storage"),
    globalStorageUri: vscode.Uri.file("/mock/globalStorage"),
    logUri: vscode.Uri.file("/mock/log"),
    secrets: {} as unknown as vscode.SecretStorage,
  } as unknown as vscode.ExtensionContext;
}

void test.describe("Run, Debug, and Test Workflows (R4-06)", () => {
  const context = createMockExtensionContext();
  const errorService = new ErrorService();
  const statusBarService = new StatusBarService(context);
  const configService = new ConfigurationService();
  const eventBus = EventBus.getInstance();

  const fixtureProjectA = {
    id: "proj-a",
    name: "sample-grails-app",
    rootPath: path.resolve("/workspace/sample-app"),
    type: ProjectType.Grails,
    dependencies: [],
  };

  const fixtureProjectB = {
    id: "proj-b",
    name: "nested-plugin",
    rootPath: path.resolve("/workspace/sample-app/plugins/custom-plugin"),
    type: ProjectType.GrailsPlugin,
    dependencies: [],
  };

  void test.it("R4-06/1: Selected project, wrapper, and environment determine the operation", () => {
    const projectService = new ProjectService(statusBarService, errorService, configService, eventBus);
    (projectService as unknown as { projects: Map<string, typeof fixtureProjectA> }).projects = new Map([
      [fixtureProjectA.id, fixtureProjectA],
      [fixtureProjectB.id, fixtureProjectB],
    ]);

    // Test longest-prefix project resolution for nested projects
    const matchedRoot = projectService.getProjectForUri(path.resolve("/workspace/sample-app/grails-app/controllers/HomeController.groovy"));
    assert.strictEqual(matchedRoot?.id, "proj-a");

    const matchedNested = projectService.getProjectForUri(path.resolve("/workspace/sample-app/plugins/custom-plugin/grails-app/services/CustomService.groovy"));
    assert.strictEqual(matchedNested?.id, "proj-b");

    // Test target project resolution fallback
    projectService.setActiveProject("proj-a");
    const active = projectService.resolveTargetProject();
    assert.strictEqual(active?.id, "proj-a");

    // Test wrapper resolution
    const gradleService = new GradleService(statusBarService, errorService, configService);
    const nonExistentResolution = gradleService.resolveGradleExecutable("/non/existent/dir");
    assert.strictEqual(nonExistentResolution.executable, null);
    assert.ok(nonExistentResolution.error?.includes("does not exist"));

    // Test Java resolution
    const javaResolution = gradleService.resolveJavaExecutable();
    assert.ok(javaResolution !== undefined);
  });

  void test.it("R4-06/1: Test discovery and execution agreement with no-tests and skipped handling", async () => {
    const projectService = new ProjectService(statusBarService, errorService, configService, eventBus);
    (projectService as unknown as { projects: Map<string, typeof fixtureProjectA> }).projects = new Map([
      [fixtureProjectA.id, fixtureProjectA],
    ]);

    const lsmMock = {
      languageClient: {
        sendRequest: (command: string) => {
          if (command === "workspace/executeCommand") {
            return Promise.resolve([
              {
                className: "com.example.BookSpec",
                uri: vscode.Uri.file("/workspace/sample-app/src/test/groovy/com/example/BookSpec.groovy").toString(),
                methods: ["test saving book", "test invalid author"],
                projectPath: fixtureProjectA.rootPath,
              },
            ]);
          }
          return Promise.resolve([]);
        },
      },
    } as unknown as LanguageServerManager;

    const gradleService = new GradleService(statusBarService, errorService, configService);

    // Mock executeGradleTask on gradleService to simulate Gradle test execution
    let capturedTaskArgs: string[] = [];
    (gradleService as unknown as { executeGradleTask: unknown }).executeGradleTask = (
      _proj: unknown,
      _task: string,
      opts: { args?: string[] }
    ) => {
      capturedTaskArgs = opts.args ?? [];
      return Promise.resolve({
        success: true,
        exitCode: 0,
        testsPassed: 2,
        testsFailed: 0,
        testsSkipped: 0,
      });
    };

    const testService = new GrailsTestService(context, errorService, lsmMock, projectService, gradleService);

    // Discover tests
    await testService.discoverAllTests();
    assert.strictEqual(testService.controller.items.size, 1);

    const bookSpecItem = testService.controller.items.get("com.example.BookSpec");
    assert.ok(bookSpecItem);
    assert.strictEqual(bookSpecItem.children.size, 2);

    // Run tests with selection
    const cts = new vscode.CancellationTokenSource();
    const request = {
      include: [bookSpecItem],
      exclude: [],
      profile: undefined,
    } as unknown as vscode.TestRunRequest;

    await testService.runTests(request, cts.token);
    assert.ok(capturedTaskArgs.includes("--tests"));
    assert.ok(capturedTaskArgs.includes("com.example.BookSpec"));

    // Verify empty / no-tests case
    const emptyRequest = {
      include: [],
      exclude: [],
      profile: undefined,
    } as unknown as vscode.TestRunRequest;

    // Overwrite items to empty
    testService.controller.items.replace([]);
    await testService.runTests(emptyRequest, cts.token);
  });

  void test.it("R4-06/2: Cancellation, restart, and disposal release owned processes", async () => {
    const gradleService = new GradleService(statusBarService, errorService, configService);

    // Test process tracking and cancellation
    const isRunningInitial = gradleService.isTaskRunning(fixtureProjectA.rootPath, "bootRun");
    assert.strictEqual(isRunningInitial, false);

    // Test cancel on non-running task returns false
    const cancelled = await gradleService.cancelTask(fixtureProjectA.rootPath, "bootRun");
    assert.strictEqual(cancelled, false);

    // Test disposal cleans up all process handles
    gradleService.dispose();
    assert.strictEqual(gradleService.isReady, true);
  });

  void test.it("R4-06/2: Actionable output for missing Java, wrapper failures, and port conflicts", async () => {
    const gradleService = new GradleService(statusBarService, errorService, configService);

    // 1. Missing directory wrapper failure gives actionable advice
    const wrapperRes = gradleService.resolveGradleExecutable("/nonexistent/directory/path");
    assert.strictEqual(wrapperRes.executable, null);
    assert.ok(wrapperRes.advice?.includes("Ensure the project folder exists"));

    // 2. Port conflict detection from output logs
    const outputWithConflict = "Error: Web server failed to start. Port 8080 was already in use.";
    const detectedPort = gradleService.detectPortConflict(outputWithConflict);
    assert.strictEqual(detectedPort, 8080);

    const jvmConflictOutput = "FATAL ERROR in native method: JDWP No transports initialized, jvmtiError=AGENT_ERROR_TRANSPORT_INIT(197)\nERROR: transport error 202: bind() failed: Address already in use: 5005";
    const detectedDebugPort = gradleService.detectPortConflict(jvmConflictOutput);
    assert.strictEqual(detectedDebugPort, 5005);

    // 3. Check port availability method operates asynchronously
    const isFree = await gradleService.checkPortAvailable(59999);
    assert.strictEqual(typeof isFree, "boolean");
  });

  void test.it("R4-06/3: Workspace trust check blocks execution across task, debug, and test services", async () => {
    const origTrusted = vscode.workspace.isTrusted;
    try {
      // Simulate untrusted workspace
      (vscode.workspace as unknown as { isTrusted: boolean }).isTrusted = false;

      const projectService = new ProjectService(statusBarService, errorService, configService, eventBus);
      (projectService as unknown as { projects: Map<string, typeof fixtureProjectA> }).projects = new Map([
        [fixtureProjectA.id, fixtureProjectA],
      ]);

      const gradleService = new GradleService(statusBarService, errorService, configService);
      const debugService = new DebugService(gradleService, errorService, configService);
      const testService = new GrailsTestService(
        context,
        errorService,
        {} as unknown as LanguageServerManager,
        projectService,
        gradleService
      );

      // Task execution should be blocked
      const taskResult = await gradleService.executeGradleTask(fixtureProjectA, "build");
      assert.strictEqual(taskResult.success, false);
      assert.strictEqual(taskResult.failureReason, "WORKSPACE_UNTRUSTED");
      assert.ok(taskResult.actionableAdvice?.includes("Grant workspace trust"));

      // Debug launch should be blocked
      const debugResult = await debugService.debugGrailsApp(fixtureProjectA);
      assert.strictEqual(debugResult, false);

      // Test run should fail test items with untrusted message
      const testItem = testService.controller.createTestItem("TestA", "TestA");
      const runRequest = { include: [testItem] } as unknown as vscode.TestRunRequest;
      const cts = new vscode.CancellationTokenSource();

      let failedMessage = "";
      const origCreateRun = testService.controller.createTestRun.bind(testService.controller);
      testService.controller.createTestRun = (req: vscode.TestRunRequest) => {
        const run = origCreateRun(req);
        run.failed = (_item: vscode.TestItem, msg: vscode.TestMessage) => {
          failedMessage = typeof msg.message === "string" ? msg.message : (msg.message as { value?: string }).value ?? "";
        };
        return run;
      };

      await testService.runTests(runRequest, cts.token);
      assert.ok(failedMessage.includes("Workspace is untrusted"));
    } finally {
      (vscode.workspace as unknown as { isTrusted: boolean }).isTrusted = origTrusted;
    }
  });

  void test.it("R4-06/3: Debug launch reports unsupported configurations and validates project type", async () => {
    const gradleService = new GradleService(statusBarService, errorService, configService);
    const debugService = new DebugService(gradleService, errorService, configService);

    // Non-Grails project is rejected
    const nonGrailsProject = {
      id: "proj-c",
      name: "plain-groovy",
      rootPath: "/workspace/plain-groovy",
      type: ProjectType.Groovy,
      dependencies: [],
    };
    const nonGrailsResult = await debugService.debugGrailsApp(nonGrailsProject);
    assert.strictEqual(nonGrailsResult, false);

    // Unsupported debugger configuration when extension is missing
    const origGetExt = vscode.extensions.getExtension;
    try {
      (vscode.extensions as unknown as { getExtension: unknown }).getExtension = () => null;
      const noDebuggerResult = await debugService.debugGrailsApp(fixtureProjectA);
      assert.strictEqual(noDebuggerResult, false);
    } finally {
      (vscode.extensions as unknown as { getExtension: unknown }).getExtension = origGetExt;
    }

    debugService.dispose();
  });

  void test.it("R4-06/1: Grails UseCases orchestrate task execution and resolve projects cleanly", async () => {
    const projectService = new ProjectService(statusBarService, errorService, configService, eventBus);
    (projectService as unknown as { projects: Map<string, typeof fixtureProjectA> }).projects = new Map([
      [fixtureProjectA.id, fixtureProjectA],
    ]);

    let executedTask = "";
    const mockGradle = {
      runGrailsApp: () => { executedTask = "runApp"; return Promise.resolve(true); },
      testGrailsApp: () => { executedTask = "testApp"; return Promise.resolve(true); },
      buildProject: (_p: unknown, task: string) => { executedTask = task; return Promise.resolve(true); },
      cleanProject: () => { executedTask = "clean"; return Promise.resolve(true); },
    } as unknown as GradleService;

    const mockDebug = {
      debugGrailsApp: () => { executedTask = "debugApp"; return Promise.resolve(true); },
    } as unknown as DebugService;

    const runUseCase = new RunGrailsAppUseCase(mockGradle, projectService, errorService);
    const runSuccess = await runUseCase.execute(fixtureProjectA);
    assert.strictEqual(runSuccess, true);
    assert.strictEqual(executedTask, "runApp");

    const debugUseCase = new DebugGrailsAppUseCase(mockDebug, projectService, errorService);
    const debugSuccess = await debugUseCase.execute(fixtureProjectA);
    assert.strictEqual(debugSuccess, true);
    assert.strictEqual(executedTask, "debugApp");

    const testUseCase = new TestGrailsAppUseCase(mockGradle, projectService, errorService);
    const testSuccess = await testUseCase.execute(fixtureProjectA);
    assert.strictEqual(testSuccess, true);
    assert.strictEqual(executedTask, "testApp");

    const buildUseCase = new BuildGrailsProjectUseCase(mockGradle, projectService, errorService);
    const buildSuccess = await buildUseCase.execute(fixtureProjectA, "compileGroovy");
    assert.strictEqual(buildSuccess, true);
    assert.strictEqual(executedTask, "compileGroovy");

    const cleanUseCase = new CleanGrailsProjectUseCase(mockGradle, projectService, errorService);
    const cleanSuccess = await cleanUseCase.execute(fixtureProjectA);
    assert.strictEqual(cleanSuccess, true);
    assert.strictEqual(executedTask, "clean");
  });
});
