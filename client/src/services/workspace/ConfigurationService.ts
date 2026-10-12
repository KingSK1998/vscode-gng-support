import type { Disposable } from "vscode";
import { ConfigurationTarget, workspace } from "vscode";

const CONFIG_SECTION = "grails";

export enum CompletionDetail {
  Basic = "basic",
  Standard = "standard",
  Advanced = "advanced",
}

export enum LogLevel {
  Error = "error",
  Warn = "warn",
  Info = "info",
  Debug = "debug",
}

export enum TraceLevel {
  Off = "off",
  Messages = "messages",
  Verbose = "verbose",
}

/**
 * Strongly-typed configuration service with user vs developer setting separation.
 * All getters provide sensible defaults matching package.json.
 */
export class ConfigurationService implements Disposable {
  private disposables: Disposable[] = [];

  /* ================= USER SETTINGS ================================= */

  /** Path to Grails installation (empty = use PATH). */
  get grailsHome(): string {
    return this.get<string>("grailsHome", "");
  }

  /** Path to Java installation (empty = use JAVA_HOME). */
  get javaHome(): string {
    return this.get<string>("javaHome", "");
  }

  /** Auto-start development server on project open. */
  get autoStartServer(): boolean {
    return this.get<boolean>("server.autoStart", false);
  }

  /** Default development server port. */
  get serverPort(): number {
    return this.get<number>("server.port", 8080);
  }

  /** Port of the manually started language server, separate from the Grails app. */
  get languageServerDevelopmentPort(): number {
    return this.get<number>("languageServer.developmentPort", 5007);
  }

  /** Completion detail level. */
  get completionDetail(): CompletionDetail {
    return this.get<CompletionDetail>("completion.detail", CompletionDetail.Advanced);
  }

  /** Maximum completion items to show. */
  get maxCompletionItems(): number {
    return this.get<number>("completion.maxItems", 1000);
  }

  /** Include code snippets in completions. */
  get includeSnippets(): boolean {
    return this.get<boolean>("completion.includeSnippets", true);
  }

  /** CodeLens enabled (run buttons, references). */
  get codeLensEnabled(): boolean {
    return this.get<boolean>("codeLens.enabled", true);
  }

  /** Diagnostics enabled (errors/warnings). */
  get diagnosticsEnabled(): boolean {
    return this.get<boolean>("diagnostics.enabled", true);
  }

  /* ================= DEVELOPER/ADVANCED ========================== */

  /** JVM arguments for language server. */
  get serverJvmArgs(): string[] {
    return this.get<string[]>("server.jvmArgs", ["-Xmx1g", "-XX:+UseG1GC"]);
  }

  /** Language server trace level for debugging. */
  get traceLevel(): TraceLevel {
    return this.get<TraceLevel>("languageServer.trace", TraceLevel.Off);
  }

  /** Project cache enabled. */
  get cacheEnabled(): boolean {
    return this.get<boolean>("cache.enabled", true);
  }

  /** Cache directory name (relative to project root). */
  get cacheDirectory(): string {
    return this.get<string>("cache.directory", ".grails-lsp");
  }

  /** Cache file name (relative to cache directory). */
  readonly cacheFile = "projectInfo.json";

  /** Experimental smart recompilation. */
  get smartRecompilation(): boolean {
    return this.get<boolean>("experimental.smartRecompilation", false);
  }

  /** Extension debug log level. */
  get logLevel(): LogLevel {
    return this.get<LogLevel>("debug.logLevel", LogLevel.Warn);
  }

  /** Extension development mode. */
  get developmentMode(): boolean {
    return this.get<boolean>("server.developmentMode", false);
  }

  /* ================= HELPERS ====================================== */

  get<T>(key: string, defaultValue: T): T {
    return workspace.getConfiguration(CONFIG_SECTION).get<T>(key, defaultValue);
  }

  async setGlobal<T>(key: string, value: T): Promise<void> {
    await workspace.getConfiguration(CONFIG_SECTION).update(key, value, ConfigurationTarget.Global);
  }

  dispose() {
    this.disposables.forEach(d => void d.dispose());
    this.disposables = [];
  }
}
