import * as path from "path";
import type { CancellationToken, Disposable } from "vscode";
import * as vscode from "vscode";
import type {
  AnalysisErrorCode,
  AnalysisErrorResponse,
  ProjectCapabilitiesRequest,
  ProjectCapabilitiesResponse,
  ProjectDiagnosticsRequest,
  ProjectDiagnosticsResponse,
  SymbolExplainRequest,
  SymbolExplainResponse,
  SymbolSearchRequest,
  SymbolSearchResponse,
} from "../../shared/protocol/analysis";
import type { ErrorService } from "../errors/ErrorService";
import { ErrorSeverity, ErrorSource } from "../errors/errorTypes";
import type { LanguageServerManager } from "../languageServer/LanguageServerManager";
import type { ConfigurationService } from "../workspace/ConfigurationService";

export class AnalysisClientError extends Error {
  constructor(
    public readonly code: AnalysisErrorCode,
    message: string,
    public readonly details?: string | null
  ) {
    super(message);
    this.name = "AnalysisClientError";
  }

  toResponse(): AnalysisErrorResponse {
    return {
      schemaVersion: 1,
      error: this.message,
      code: this.code,
      details: this.details ?? null,
    };
  }
}

/**
 * Service for exposing Grails analysis operations to VS Code language model tools
 * and deterministic editor commands.
 */
export class AnalysisToolService implements Disposable {
  private _disposed = false;
  private readonly _toolDisposables: Disposable[] = [];

  constructor(
    private readonly languageServerManager: LanguageServerManager,
    private readonly errorService: ErrorService,
    private readonly configurationService: ConfigurationService
  ) {}

  get isDisposed(): boolean {
    return this._disposed;
  }

  /**
   * Validates workspace trust and bounds.
   */
  private checkPreconditions(projectUri: string, documentUri?: string | null): void {
    if (this._disposed) {
      throw new AnalysisClientError("UNAVAILABLE", "AnalysisToolService is disposed.");
    }

    if (!vscode.workspace.isTrusted) {
      throw new AnalysisClientError(
        "UNAVAILABLE",
        "Workspace is untrusted. Analysis operations are disabled in untrusted workspaces."
      );
    }

    if (!projectUri || typeof projectUri !== "string") {
      throw new AnalysisClientError("INVALID_INPUT", "projectUri is required and must be a string.");
    }

    this.validateWorkspaceScope(projectUri);

    if (documentUri) {
      this.validateWorkspaceScope(documentUri);
    }

    if (!this.languageServerManager.isRunning || !this.languageServerManager.languageClient) {
      throw new AnalysisClientError("UNAVAILABLE", "Language server is not running or ready.");
    }
  }

  private validateWorkspaceScope(uriString: string): void {
    let uri: vscode.Uri;
    try {
      uri = vscode.Uri.parse(uriString);
    } catch {
      throw new AnalysisClientError("INVALID_INPUT", `Invalid URI format: '${uriString}'.`);
    }

    const folder = vscode.workspace.getWorkspaceFolder(uri);
    if (!folder && vscode.workspace.workspaceFolders && vscode.workspace.workspaceFolders.length > 0) {
      const normalizedPath = path.resolve(uri.fsPath).toLowerCase();
      const inScope = vscode.workspace.workspaceFolders.some(f => {
        const rootPath = path.resolve(f.uri.fsPath).toLowerCase();
        return normalizedPath === rootPath || normalizedPath.startsWith(rootPath + path.sep);
      });
      if (!inScope) {
        throw new AnalysisClientError(
          "INVALID_INPUT",
          `Target URI '${uriString}' is outside open workspace bounds.`
        );
      }
    }
  }

  /**
   * Execute project.capabilities operation.
   */
  async getProjectCapabilities(
    req: ProjectCapabilitiesRequest,
    token?: CancellationToken
  ): Promise<ProjectCapabilitiesResponse> {
    if (!req || typeof req !== "object") {
      throw new AnalysisClientError("INVALID_INPUT", "Request payload is required and must be an object.");
    }
    this.checkPreconditions(req.projectUri);

    try {
      const client = this.languageServerManager.languageClient!;
      const rawResult = await client.sendRequest<unknown>(
        "workspace/executeCommand",
        {
          command: "grails.analysis.projectCapabilities",
          arguments: [req],
        },
        token
      );

      this.checkErrorResponse(rawResult);
      return rawResult as ProjectCapabilitiesResponse;
    } catch (error) {
      return this.handleOperationError("projectCapabilities", error);
    }
  }

  /**
   * Execute symbols.search operation with defensive count and byte limits.
   */
  async searchSymbols(
    req: SymbolSearchRequest,
    token?: CancellationToken
  ): Promise<SymbolSearchResponse> {
    if (!req || typeof req !== "object") {
      throw new AnalysisClientError("INVALID_INPUT", "Request payload is required and must be an object.");
    }
    this.checkPreconditions(req.projectUri, req.uri);

    if (!req.query || typeof req.query !== "string") {
      throw new AnalysisClientError("INVALID_INPUT", "query is required and must be a string.");
    }

    const boundedReq: SymbolSearchRequest = {
      ...req,
      limit: Math.min(req.limit ?? 50, 200),
      maxPayloadBytes: Math.min(req.maxPayloadBytes ?? 65536, 262144),
    };

    try {
      const client = this.languageServerManager.languageClient!;
      const rawResult = await client.sendRequest<unknown>(
        "workspace/executeCommand",
        {
          command: "grails.analysis.symbolSearch",
          arguments: [boundedReq],
        },
        token
      );

      this.checkErrorResponse(rawResult);
      return rawResult as SymbolSearchResponse;
    } catch (error) {
      return this.handleOperationError("symbolSearch", error);
    }
  }

  /**
   * Execute symbols.explain operation.
   */
  async explainSymbol(
    req: SymbolExplainRequest,
    token?: CancellationToken
  ): Promise<SymbolExplainResponse> {
    if (!req || typeof req !== "object") {
      throw new AnalysisClientError("INVALID_INPUT", "Request payload is required and must be an object.");
    }
    this.checkPreconditions(req.projectUri, req.documentUri);

    if (!req.documentUri || typeof req.documentUri !== "string") {
      throw new AnalysisClientError("INVALID_INPUT", "documentUri is required.");
    }

    try {
      const client = this.languageServerManager.languageClient!;
      const rawResult = await client.sendRequest<unknown>(
        "workspace/executeCommand",
        {
          command: "grails.analysis.symbolExplain",
          arguments: [req],
        },
        token
      );

      this.checkErrorResponse(rawResult);
      return rawResult as SymbolExplainResponse;
    } catch (error) {
      return this.handleOperationError("symbolExplain", error);
    }
  }

  /**
   * Execute project.diagnostics operation with defensive count limit.
   */
  async getProjectDiagnostics(
    req: ProjectDiagnosticsRequest,
    token?: CancellationToken
  ): Promise<ProjectDiagnosticsResponse> {
    if (!req || typeof req !== "object") {
      throw new AnalysisClientError("INVALID_INPUT", "Request payload is required and must be an object.");
    }
    this.checkPreconditions(req.projectUri, req.documentUri);

    const boundedReq: ProjectDiagnosticsRequest = {
      ...req,
      limit: Math.min(req.limit ?? 100, 500),
    };

    try {
      const client = this.languageServerManager.languageClient!;
      const rawResult = await client.sendRequest<unknown>(
        "workspace/executeCommand",
        {
          command: "grails.analysis.projectDiagnostics",
          arguments: [boundedReq],
        },
        token
      );

      this.checkErrorResponse(rawResult);
      return rawResult as ProjectDiagnosticsResponse;
    } catch (error) {
      return this.handleOperationError("projectDiagnostics", error);
    }
  }

  private checkErrorResponse(raw: unknown): void {
    if (
      raw &&
      typeof raw === "object" &&
      "code" in raw &&
      "error" in raw &&
      !("schemaVersion" in raw && "projectUri" in raw)
    ) {
      const err = raw as AnalysisErrorResponse;
      throw new AnalysisClientError(err.code, err.error, err.details);
    }
  }

  private handleOperationError(operation: string, error: unknown): never {
    if (error instanceof AnalysisClientError) {
      this.errorService.handleError(
        `[Analysis] ${operation} failed: ${error.message}`,
        error,
        ErrorSource.LanguageServer,
        ErrorSeverity.Warning
      );
      throw error;
    }

    const message = error instanceof Error ? error.message : String(error);
    const clientErr = new AnalysisClientError("INTERNAL_ERROR", message);
    this.errorService.handleError(
      `[Analysis] ${operation} internal error: ${message}`,
      error,
      ErrorSource.LanguageServer,
      ErrorSeverity.Error
    );
    throw clientErr;
  }

  /**
   * Registers VS Code Language Model Tools if vscode.lm is supported by the host.
   */
  registerLanguageModelTools(): Disposable {
    if (this._disposed) {
      return {
        dispose: () => {
          // No-op: service already disposed
        },
      };
    }

    if (
      typeof vscode.lm === "undefined" ||
      typeof vscode.lm.registerTool !== "function"
    ) {
      return {
        dispose: () => {
          // No-op: host environment does not provide vscode.lm.registerTool
        },
      };
    }

    const d1 = vscode.lm.registerTool("grails_project_capabilities", {
      invoke: async (options, token) => {
        try {
          const res = await this.getProjectCapabilities(options.input as ProjectCapabilitiesRequest, token);
          return new vscode.LanguageModelToolResult([
            new vscode.LanguageModelTextPart(JSON.stringify(res, null, 2)),
          ]);
        } catch (err) {
          const res = err instanceof AnalysisClientError ? err.toResponse() : {
            schemaVersion: 1,
            error: String(err),
            code: "INTERNAL_ERROR",
          };
          return new vscode.LanguageModelToolResult([
            new vscode.LanguageModelTextPart(JSON.stringify(res, null, 2)),
          ]);
        }
      },
    });

    const d2 = vscode.lm.registerTool("grails_symbols_search", {
      invoke: async (options, token) => {
        try {
          const res = await this.searchSymbols(options.input as SymbolSearchRequest, token);
          return new vscode.LanguageModelToolResult([
            new vscode.LanguageModelTextPart(JSON.stringify(res, null, 2)),
          ]);
        } catch (err) {
          const res = err instanceof AnalysisClientError ? err.toResponse() : {
            schemaVersion: 1,
            error: String(err),
            code: "INTERNAL_ERROR",
          };
          return new vscode.LanguageModelToolResult([
            new vscode.LanguageModelTextPart(JSON.stringify(res, null, 2)),
          ]);
        }
      },
    });

    const d3 = vscode.lm.registerTool("grails_symbols_explain", {
      invoke: async (options, token) => {
        try {
          const res = await this.explainSymbol(options.input as SymbolExplainRequest, token);
          return new vscode.LanguageModelToolResult([
            new vscode.LanguageModelTextPart(JSON.stringify(res, null, 2)),
          ]);
        } catch (err) {
          const res = err instanceof AnalysisClientError ? err.toResponse() : {
            schemaVersion: 1,
            error: String(err),
            code: "INTERNAL_ERROR",
          };
          return new vscode.LanguageModelToolResult([
            new vscode.LanguageModelTextPart(JSON.stringify(res, null, 2)),
          ]);
        }
      },
    });

    const d4 = vscode.lm.registerTool("grails_project_diagnostics", {
      invoke: async (options, token) => {
        try {
          const res = await this.getProjectDiagnostics(options.input as ProjectDiagnosticsRequest, token);
          return new vscode.LanguageModelToolResult([
            new vscode.LanguageModelTextPart(JSON.stringify(res, null, 2)),
          ]);
        } catch (err) {
          const res = err instanceof AnalysisClientError ? err.toResponse() : {
            schemaVersion: 1,
            error: String(err),
            code: "INTERNAL_ERROR",
          };
          return new vscode.LanguageModelToolResult([
            new vscode.LanguageModelTextPart(JSON.stringify(res, null, 2)),
          ]);
        }
      },
    });

    const combined: Disposable = {
      dispose: () => {
        d1.dispose();
        d2.dispose();
        d3.dispose();
        d4.dispose();
      },
    };

    this._toolDisposables.push(combined);
    return combined;
  }

  dispose(): void {
    if (this._disposed) {
      return;
    }
    this._disposed = true;

    for (const d of this._toolDisposables) {
      try {
        d.dispose();
      } catch (err) {
        console.warn("[AnalysisToolService] Error disposing tool registration:", err);
      }
    }
    this._toolDisposables.length = 0;
  }
}
