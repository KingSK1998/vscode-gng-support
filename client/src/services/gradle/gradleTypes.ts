import type { CancellationToken, Disposable, Event, Task, TaskProvider } from "vscode";
// https://github.com/microsoft/vscode-gradle/blob/develop/API.md

export declare class Api {
  // run a Gradle task
  runTask(opts: RunTaskOpts): Promise<void>;
  cancelRunTask(opts: CancelTaskOpts): Promise<void>;
  // run a Gradle build
  runBuild(opts: RunBuildOpts): Promise<void>;
  cancelRunBuild(opts: CancelBuildOpts): Promise<void>;
  // get the task provider for gradle confirmation
  getTaskProvider(): GradleTaskProvider;
}

declare class GradleTaskProvider implements TaskProvider, Disposable {
  readonly onDidLoadTasks: Event<Task[]>;
  provideTasks(): Promise<Task[] | undefined>;
  resolveTask(_task: Task): Promise<Task | undefined>;
  dispose(): void;
}

// Gradle API Types
export interface GradleTaskResult {
  success: boolean;
  message: string;
  data?: string[];
}

export interface RunTaskOpts {
  projectFolder: string; // "/absolute/path/to/project/root"
  taskName: string; // "help"
  showOutputColors: boolean; // true
  onOutput?: (output: Output) => void; // { const msg = new util.TextDecoder("utf-8").decode(output.getOutputBytes_asU8()); console.log(output.getOutputType(), message);}
  cancellationKey?: string;
}

export interface RunBuildOpts {
  projectFolder: string;
  args: readonly string[];
  input?: string;
  onOutput?: (output: unknown) => void;
  showOutputColors: boolean;
  cancellationKey?: string;
}

export interface CancelTaskOpts {
  projectFolder?: string;
  taskName?: string;
  cancellationKey?: string;
}

export interface CancelBuildOpts {
  projectFolder?: string;
  args?: readonly string[];
  cancellationKey?: string;
}

interface Output {
  getOutputType(): unknown;
  getOutputBytes_asU8(): Uint8Array;
}

/** Execution failure reasons for actionable error reporting */
export type ExecutionFailureReason =
  | "WORKSPACE_UNTRUSTED"
  | "JAVA_MISSING"
  | "WRAPPER_MISSING"
  | "PORT_CONFLICT"
  | "EXECUTION_FAILED"
  | "CANCELLED"
  | "TIMEOUT";

export interface TaskExecutionOptions {
  args?: string[] | undefined;
  cancellationToken?: CancellationToken | undefined;
  onOutput?: ((chunk: string) => void) | undefined;
  env?: Record<string, string> | undefined;
  portCheck?: number | undefined;
}

export interface TaskExecutionResult {
  success: boolean;
  exitCode?: number | undefined;
  error?: string | undefined;
  failureReason?: ExecutionFailureReason | undefined;
  actionableAdvice?: string | undefined;
  output?: string | undefined;
  noTests?: boolean | undefined;
  testsPassed?: number | undefined;
  testsFailed?: number | undefined;
  testsSkipped?: number | undefined;
}

/** Common Grails tasks for quick access */
export enum GrailsTask {
  BootRun = "bootRun",
  RunApp = "run-app",
  Test = "test",
  TestApp = "test-app",
  CreateController = "create-controller",
  CreateService = "create-service",
  CreateDomain = "create-domain-class",
  Build = "build",
  Clean = "clean",
  Compile = "compile",
  CompileGroovy = "compileGroovy",
  War = "war",
}
