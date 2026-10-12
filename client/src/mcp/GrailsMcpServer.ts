import { EventEmitter } from "events";
import type { Readable, Writable } from "stream";
import type {
  AnalysisErrorResponse,
  ProjectCapabilitiesRequest,
  ProjectDiagnosticsRequest,
  SymbolExplainRequest,
  SymbolSearchRequest,
} from "../shared/protocol/analysis";

export interface AnalysisBackend {
  executeCommand<T = unknown>(
    command: string,
    args: unknown[],
    abortSignal?: AbortSignal
  ): Promise<T>;
}

export interface McpToolDefinition {
  name: string;
  description: string;
  inputSchema: {
    type: "object";
    properties: Record<string, unknown>;
    required?: string[];
  };
}

export type McpServerState = "UNINITIALIZED" | "INITIALIZING" | "READY" | "DISPOSED";

/**
 * Model Context Protocol (MCP) server exposing Grails analysis read operations
 * to external clients (e.g. Claude Desktop, Cursor, standalone CLI agents).
 * Conforms to Model Context Protocol (2024-11-05), INV-TOOL-001 (shared analysis boundary),
 * and INV-TOOL-003 (boundary validation for trust, scope, and bounds).
 */
export class GrailsMcpServer extends EventEmitter {
  private _state: McpServerState = "UNINITIALIZED";
  private readonly activeRequests = new Map<string | number, AbortController>();
  private buffer = "";

  public static readonly PROTOCOL_VERSION = "2024-11-05";
  public static readonly SERVER_NAME = "grails-mcp-server";
  public static readonly SERVER_VERSION = "0.2.0";

  public static readonly TOOLS: McpToolDefinition[] = [
    {
      name: "grails_project_capabilities",
      description:
        "Inspect Grails project capabilities, detected versions (Grails, Groovy, JDK, Gradle), source sets, plugins, and active capability adapters (GORM, Injection, TagLib, Config).",
      inputSchema: {
        type: "object",
        properties: {
          projectUri: {
            type: "string",
            description: "URI of the project root (e.g. file:///path/to/project)",
          },
          sourceSet: {
            type: "string",
            description: "Optional source set name (default: main)",
          },
        },
        required: ["projectUri"],
      },
    },
    {
      name: "grails_symbols_search",
      description:
        "Search indexed symbols, declarations, methods, properties, and dynamic finders across a Grails project with query filtering, kind filtering, and pagination.",
      inputSchema: {
        type: "object",
        properties: {
          projectUri: {
            type: "string",
            description: "URI of the project root",
          },
          query: {
            type: "string",
            description: "Search prefix or pattern (e.g. 'Book', 'findByTitle')",
          },
          uri: {
            type: "string",
            description: "Optional document URI to scope results",
          },
          sourceSet: {
            type: "string",
            description: "Optional source set name",
          },
          kinds: {
            type: "array",
            items: { type: "string" },
            description: "Optional list of symbol kinds to filter (e.g. ['Class', 'Method'])",
          },
          limit: {
            type: "integer",
            minimum: 1,
            maximum: 200,
            description: "Maximum results to return (default 50, capped at 200)",
          },
          maxPayloadBytes: {
            type: "integer",
            minimum: 1024,
            maximum: 262144,
            description: "Maximum payload bytes (default 65536, capped at 262144)",
          },
          cursor: {
            type: "string",
            description: "Opaque pagination cursor from a previous search result",
          },
        },
        required: ["projectUri", "query"],
      },
    },
    {
      name: "grails_symbols_explain",
      description:
        "Explain a resolved Grails symbol declaration with exact return type, parameters, documentation, origin kind, and provenance.",
      inputSchema: {
        type: "object",
        properties: {
          projectUri: {
            type: "string",
            description: "URI of the project root",
          },
          documentUri: {
            type: "string",
            description: "URI of the document containing the symbol",
          },
          symbolName: {
            type: "string",
            description: "Name of the symbol to explain",
          },
          line: {
            type: "integer",
            minimum: 0,
            description: "Optional 0-indexed line number in document",
          },
          character: {
            type: "integer",
            minimum: 0,
            description: "Optional 0-indexed character offset in document",
          },
        },
        required: ["projectUri", "documentUri"],
      },
    },
    {
      name: "grails_project_diagnostics",
      description:
        "Get compiler, framework, and lint diagnostics for a Grails project or specific file, reading committed disk state and reporting severity, line, and cause.",
      inputSchema: {
        type: "object",
        properties: {
          projectUri: {
            type: "string",
            description: "URI of the project root",
          },
          documentUri: {
            type: "string",
            description: "Optional document URI to scope diagnostics to a single file",
          },
          severity: {
            type: "string",
            enum: ["Error", "Warning", "Information", "Hint"],
            description: "Optional minimum severity filter",
          },
          limit: {
            type: "integer",
            minimum: 1,
            maximum: 500,
            description: "Maximum diagnostics to return (default 100, capped at 500)",
          },
        },
        required: ["projectUri"],
      },
    },
    {
      name: "grails_change_review",
      description:
        "Present unified change review connecting proposed edits, source evidence, change impact, check execution status, and unchecked assumptions.",
      inputSchema: {
        type: "object",
        properties: {
          projectUri: {
            type: "string",
            description: "URI of the project root",
          },
          changeId: {
            type: "string",
            description: "Optional identifier for this proposed or applied change",
          },
          edits: {
            type: "array",
            items: { type: "object" },
            description: "Proposed RenameEdit list",
          },
          impact: {
            type: "object",
            description: "Optional ChangeImpactResponse",
          },
          checks: {
            type: "object",
            description: "Optional CheckExecutionResponse",
          },
          assumptions: {
            type: "array",
            items: { type: "string" },
            description: "Unchecked assumptions declared by the agent",
          },
        },
        required: ["projectUri"],
      },
    },
  ];

  constructor(
    private readonly backend: AnalysisBackend,
    private readonly input: Readable,
    private readonly output: Writable
  ) {
    super();
    this.setupStreamListeners();
  }

  get state(): McpServerState {
    return this._state;
  }

  get isDisposed(): boolean {
    return this._state === "DISPOSED";
  }

  private setupStreamListeners(): void {
    this.input.on("data", (chunk: Buffer | string) => {
      this.handleIncomingData(chunk.toString("utf8"));
    });

    this.input.on("end", () => {
      this.dispose();
    });

    this.input.on("close", () => {
      this.dispose();
    });

    this.input.on("error", err => {
      this.emit("error", err);
      this.dispose();
    });
  }

  private handleIncomingData(chunk: string): void {
    if (this._state === "DISPOSED") {
      return;
    }

    this.buffer += chunk;

    // Check for Content-Length framing vs newline-delimited framing
    while (this.buffer.length > 0) {
      if (this.buffer.startsWith("Content-Length:")) {
        const headerEnd = this.buffer.indexOf("\r\n\r\n");
        if (headerEnd === -1) {
          return; // Wait for full header
        }

        const match = this.buffer.match(/Content-Length:\s*(\d+)/i);
        if (!match) {
          this.sendJsonRpcError(null, -32700, "Parse error: Invalid Content-Length header.");
          this.buffer = "";
          return;
        }

        const length = parseInt(match[1], 10);
        const bodyStart = headerEnd + 4;
        if (this.buffer.length < bodyStart + length) {
          return; // Wait for full body
        }

        const rawBody = this.buffer.slice(bodyStart, bodyStart + length);
        this.buffer = this.buffer.slice(bodyStart + length);
        this.processRawMessage(rawBody);
      } else {
        // Line-delimited JSON
        const newlineIndex = this.buffer.indexOf("\n");
        if (newlineIndex === -1) {
          return; // Wait for newline
        }

        const line = this.buffer.slice(0, newlineIndex).trim();
        this.buffer = this.buffer.slice(newlineIndex + 1);

        if (line.length > 0) {
          this.processRawMessage(line);
        }
      }
    }
  }

  private processRawMessage(raw: string): void {
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      this.sendJsonRpcError(null, -32700, "Parse error: Invalid JSON.");
      return;
    }

    if (!parsed || typeof parsed !== "object") {
      this.sendJsonRpcError(null, -32600, "Invalid Request: Message must be an object.");
      return;
    }

    const msg = parsed as Record<string, unknown>;
    if (msg.jsonrpc !== "2.0") {
      this.sendJsonRpcError(
        msg.id as string | number | null,
        -32600,
        "Invalid Request: 'jsonrpc' must be '2.0'."
      );
      return;
    }

    this.handleMessage(msg).catch(err => {
      this.sendJsonRpcError(
        msg.id as string | number | null,
        -32603,
        err instanceof Error ? err.message : "Internal error"
      );
    });
  }

  private async handleMessage(msg: Record<string, unknown>): Promise<void> {
    const method = msg.method;
    const id = msg.id as string | number | undefined;

    if (typeof method !== "string") {
      if (typeof id !== "undefined") {
        this.sendJsonRpcError(id, -32600, "Invalid Request: 'method' must be a string.");
      }
      return;
    }

    // Notifications (no id)
    if (typeof id === "undefined") {
      this.handleNotification(method, msg.params);
      return;
    }

    // Requests (has id)
    switch (method) {
      case "initialize":
        this.handleInitialize(id, msg.params);
        break;
      case "ping":
        this.sendResult(id, {});
        break;
      case "tools/list":
        this.handleToolsList(id);
        break;
      case "tools/call":
        await this.handleToolsCall(id, msg.params);
        break;
      case "shutdown":
        this.sendResult(id, null);
        this.dispose();
        break;
      default:
        this.sendJsonRpcError(id, -32601, `Method not found: '${method}'.`);
        break;
    }
  }

  private handleNotification(method: string, params: unknown): void {
    if (method === "notifications/initialized") {
      if (this._state === "INITIALIZING") {
        this._state = "READY";
      }
    } else if (method === "notifications/cancelled") {
      if (params && typeof params === "object") {
        const reqId = (params as { requestId?: string | number }).requestId;
        if (typeof reqId !== "undefined") {
          const controller = this.activeRequests.get(reqId);
          if (controller) {
            controller.abort();
            this.activeRequests.delete(reqId);
          }
        }
      }
    }
  }

  private handleInitialize(id: string | number, _params: unknown): void {
    this._state = "INITIALIZING";

    this.sendResult(id, {
      protocolVersion: GrailsMcpServer.PROTOCOL_VERSION,
      capabilities: {
        tools: {},
      },
      serverInfo: {
        name: GrailsMcpServer.SERVER_NAME,
        version: GrailsMcpServer.SERVER_VERSION,
      },
    });
  }

  private handleToolsList(id: string | number): void {
    this.sendResult(id, {
      tools: GrailsMcpServer.TOOLS,
    });
  }

  private async handleToolsCall(id: string | number, rawParams: unknown): Promise<void> {
    if (!rawParams || typeof rawParams !== "object") {
      this.sendJsonRpcError(id, -32602, "Invalid params: 'params' must be an object.");
      return;
    }

    const { name, arguments: args } = rawParams as {
      name?: unknown;
      arguments?: unknown;
    };

    if (typeof name !== "string") {
      this.sendJsonRpcError(id, -32602, "Invalid params: 'name' must be a string.");
      return;
    }

    if (!args || typeof args !== "object") {
      this.sendJsonRpcError(id, -32602, "Invalid params: 'arguments' must be an object.");
      return;
    }

    const abortController = new AbortController();
    this.activeRequests.set(id, abortController);

    try {
      const result = await this.executeToolCall(
        name,
        args as Record<string, unknown>,
        abortController.signal
      );
      this.sendResult(id, result);
    } finally {
      this.activeRequests.delete(id);
    }
  }

  /**
   * Executes a tool call, returning an MCP ToolResult ({ content: [...], isError?: boolean }).
   */
  private async executeToolCall(
    name: string,
    args: Record<string, unknown>,
    abortSignal: AbortSignal
  ): Promise<{ content: { type: "text"; text: string }[]; isError?: boolean }> {
    // 1. Boundary validation (INV-TOOL-003)
    const validationError = this.validateToolArguments(name, args);
    if (validationError) {
      return {
        content: [{ type: "text", text: JSON.stringify(validationError, null, 2) }],
        isError: true,
      };
    }

    // 2. Map tool name to LSP analysis command
    let lspCommand: string;
    let boundedArgs: unknown;

    switch (name) {
      case "grails_project_capabilities": {
        lspCommand = "grails.analysis.projectCapabilities";
        const req: ProjectCapabilitiesRequest = {
          projectUri: args.projectUri as string,
          sourceSet: (args.sourceSet as string | undefined) ?? null,
        };
        boundedArgs = req;
        break;
      }
      case "grails_symbols_search": {
        lspCommand = "grails.analysis.symbolSearch";
        const limit = typeof args.limit === "number" ? Math.min(Math.max(args.limit, 1), 200) : 50;
        const maxPayloadBytes =
          typeof args.maxPayloadBytes === "number"
            ? Math.min(Math.max(args.maxPayloadBytes, 1024), 262144)
            : 65536;

        const req: SymbolSearchRequest = {
          projectUri: args.projectUri as string,
          query: args.query as string,
          uri: (args.uri as string | undefined) ?? null,
          sourceSet: (args.sourceSet as string | undefined) ?? null,
          kinds: Array.isArray(args.kinds) ? args.kinds.map(String) : null,
          limit,
          maxPayloadBytes,
          cursor: (args.cursor as string | undefined) ?? null,
        };
        boundedArgs = req;
        break;
      }
      case "grails_symbols_explain": {
        lspCommand = "grails.analysis.symbolExplain";
        const req: SymbolExplainRequest = {
          projectUri: args.projectUri as string,
          documentUri: args.documentUri as string,
          symbolName: (args.symbolName as string | undefined) ?? null,
          line: typeof args.line === "number" ? args.line : null,
          character: typeof args.character === "number" ? args.character : null,
        };
        boundedArgs = req;
        break;
      }
      case "grails_project_diagnostics": {
        lspCommand = "grails.analysis.projectDiagnostics";
        const limit = typeof args.limit === "number" ? Math.min(Math.max(args.limit, 1), 500) : 100;
        const validSeverities = ["Error", "Warning", "Information", "Hint"] as const;
        type DiagSeverity = (typeof validSeverities)[number];
        const severityStr = typeof args.severity === "string" ? args.severity : undefined;
        const severity: DiagSeverity | null = validSeverities.includes(severityStr as DiagSeverity)
          ? (severityStr as DiagSeverity)
          : null;

        const req: ProjectDiagnosticsRequest = {
          projectUri: args.projectUri as string,
          documentUri: (args.documentUri as string | undefined) ?? null,
          severity,
          limit,
        };
        boundedArgs = req;
        break;
      }
      case "grails_change_review": {
        lspCommand = "grails.analysis.changeReview";
        boundedArgs = args;
        break;
      }
      default:
        return {
          content: [
            {
              type: "text",
              text: JSON.stringify(
                {
                  schemaVersion: 1,
                  error: `Unknown tool: '${name}'.`,
                  code: "INVALID_INPUT",
                  details: null,
                } satisfies AnalysisErrorResponse,
                null,
                2
              ),
            },
          ],
          isError: true,
        };
    }

    try {
      const rawResult = await this.backend.executeCommand<unknown>(
        lspCommand,
        [boundedArgs],
        abortSignal
      );

      // Check if backend returned an AnalysisErrorResponse
      if (
        rawResult &&
        typeof rawResult === "object" &&
        "error" in rawResult &&
        "code" in rawResult
      ) {
        return {
          content: [{ type: "text", text: JSON.stringify(rawResult, null, 2) }],
          isError: true,
        };
      }

      // Format response with explicit diskFacts availability declaration (R3-04/2)
      const formattedResult = {
        ...(rawResult as Record<string, unknown>),
        clientMode: "diskFacts",
        unsavedEditorBuffersAvailable: false,
      };

      return {
        content: [{ type: "text", text: JSON.stringify(formattedResult, null, 2) }],
        isError: false,
      };
    } catch (err: unknown) {
      if (abortSignal.aborted) {
        return {
          content: [
            {
              type: "text",
              text: JSON.stringify(
                {
                  schemaVersion: 1,
                  error: "Operation cancelled by client.",
                  code: "CANCELLED",
                  details: null,
                } satisfies AnalysisErrorResponse,
                null,
                2
              ),
            },
          ],
          isError: true,
        };
      }

      const msg = err instanceof Error ? err.message : String(err);
      return {
        content: [
          {
            type: "text",
            text: JSON.stringify(
              {
                schemaVersion: 1,
                error: msg,
                code: "INTERNAL_ERROR",
                details: null,
              } satisfies AnalysisErrorResponse,
              null,
              2
            ),
          },
        ],
        isError: true,
      };
    }
  }

  private validateToolArguments(
    name: string,
    args: Record<string, unknown>
  ): AnalysisErrorResponse | null {
    if (!args.projectUri || typeof args.projectUri !== "string") {
      return {
        schemaVersion: 1,
        error: "projectUri is required and must be a valid URI string.",
        code: "INVALID_INPUT",
        details: null,
      };
    }

    // URI format validation
    if (!args.projectUri.startsWith("file:///") && !args.projectUri.startsWith("file:/")) {
      return {
        schemaVersion: 1,
        error: `Invalid projectUri format: '${args.projectUri}'. Must be a file:// URI.`,
        code: "INVALID_INPUT",
        details: null,
      };
    }

    if (name === "grails_symbols_search") {
      if (!args.query || typeof args.query !== "string") {
        return {
          schemaVersion: 1,
          error: "query is required and must be a non-empty string.",
          code: "INVALID_INPUT",
          details: null,
        };
      }
      if (args.uri && typeof args.uri === "string") {
        if (!args.uri.startsWith("file:///") && !args.uri.startsWith("file:/")) {
          return {
            schemaVersion: 1,
            error: `Invalid uri format: '${args.uri}'. Must be a file:// URI.`,
            code: "INVALID_INPUT",
            details: null,
          };
        }
      }
    }

    if (name === "grails_symbols_explain") {
      if (!args.documentUri || typeof args.documentUri !== "string") {
        return {
          schemaVersion: 1,
          error: "documentUri is required and must be a valid URI string.",
          code: "INVALID_INPUT",
          details: null,
        };
      }
      if (!args.documentUri.startsWith("file:///") && !args.documentUri.startsWith("file:/")) {
        return {
          schemaVersion: 1,
          error: `Invalid documentUri format: '${args.documentUri}'. Must be a file:// URI.`,
          code: "INVALID_INPUT",
          details: null,
        };
      }
    }

    if (name === "grails_project_diagnostics") {
      if (args.documentUri && typeof args.documentUri === "string") {
        if (!args.documentUri.startsWith("file:///") && !args.documentUri.startsWith("file:/")) {
          return {
            schemaVersion: 1,
            error: `Invalid documentUri format: '${args.documentUri}'. Must be a file:// URI.`,
            code: "INVALID_INPUT",
            details: null,
          };
        }
      }
    }

    return null;
  }

  private sendResult(id: string | number, result: unknown): void {
    this.sendRaw({
      jsonrpc: "2.0",
      id,
      result,
    });
  }

  private sendJsonRpcError(
    id: string | number | null,
    code: number,
    message: string,
    data?: unknown
  ): void {
    this.sendRaw({
      jsonrpc: "2.0",
      id,
      error: {
        code,
        message,
        data,
      },
    });
  }

  private sendRaw(obj: Record<string, unknown>): void {
    if (this._state === "DISPOSED") {
      return;
    }
    const serialized = JSON.stringify(obj) + "\n";
    this.output.write(serialized);
  }

  dispose(): void {
    if (this._state === "DISPOSED") {
      return;
    }
    this._state = "DISPOSED";

    // Abort all in-flight request controllers
    for (const controller of this.activeRequests.values()) {
      try {
        controller.abort();
      } catch {
        // ignore
      }
    }
    this.activeRequests.clear();
    this.emit("close");
  }
}
