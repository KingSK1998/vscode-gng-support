import { equal, ok } from "node:assert/strict";
import { test } from "node:test";
import { PassThrough } from "stream";
import { GrailsMcpServer, type AnalysisBackend } from "../mcp/GrailsMcpServer";
import type {
  AnalysisErrorResponse,
  ProjectCapabilitiesResponse,
  ProjectDiagnosticsResponse,
  SymbolExplainResponse,
  SymbolSearchResponse,
} from "../shared/protocol/analysis";

class MockAnalysisBackend implements AnalysisBackend {
  public lastCommand: string | null = null;
  public lastArgs: unknown[] | null = null;
  public simulateDelayMs = 0;
  public throwOnCommand = false;
  public returnErrorResponse: AnalysisErrorResponse | null = null;

  async executeCommand<T = unknown>(
    command: string,
    args: unknown[],
    abortSignal?: AbortSignal
  ): Promise<T> {
    this.lastCommand = command;
    this.lastArgs = args;

    if (this.simulateDelayMs > 0) {
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => resolve(), this.simulateDelayMs);
        if (abortSignal) {
          abortSignal.addEventListener("abort", () => {
            clearTimeout(timer);
            reject(new Error("Operation aborted."));
          });
        }
      });
    }

    if (abortSignal?.aborted) {
      throw new Error("Aborted");
    }

    if (this.returnErrorResponse) {
      return this.returnErrorResponse as unknown as T;
    }

    if (this.throwOnCommand) {
      throw new Error("Simulated backend failure");
    }

    switch (command) {
      case "grails.analysis.projectCapabilities":
        return {
          schemaVersion: 1,
          analysisRevision: "rev:1:dep:0",
          projectName: "McpTestApp",
          projectUri: (args[0] as { projectUri: string }).projectUri,
          grailsVersion: "7.0.0-RC1",
          groovyVersion: "4.0.23",
          jvmTarget: "17",
          sourceSets: ["main", "test"],
          capabilities: [{ name: "GORM", supported: true }],
          limitations: [],
        } as unknown as T;

      case "grails.analysis.symbolSearch":
        return {
          schemaVersion: 1,
          analysisRevision: "rev:1:dep:0",
          freshness: "current",
          completeness: "complete",
          items: [
            {
              name: "BookController",
              kind: "Class",
              detail: "class BookController",
              uri: "file:///workspace/grails-app/controllers/BookController.groovy",
              range: { start: { line: 0, character: 0 }, end: { line: 10, character: 0 } },
              origin: { kind: "SOURCE", artifact: "Controller" },
            },
          ],
          totalEstimated: 1,
          limitations: [],
        } as unknown as T;

      case "grails.analysis.symbolExplain":
        return {
          schemaVersion: 1,
          analysisRevision: "rev:1:dep:0",
          symbol: {
            name: "findByTitle",
            kind: "Method",
            detail: "Book findByTitle(String title)",
            uri: "file:///workspace/grails-app/domain/Book.groovy",
            range: { start: { line: 5, character: 4 }, end: { line: 5, character: 15 } },
            origin: { kind: "CAPABILITY_ADAPTER", artifact: "org.grails:grails-datastore-gorm" },
          },
          documentation: "Dynamic finder for title property",
          references: [],
        } as unknown as T;

      case "grails.analysis.projectDiagnostics":
        return {
          schemaVersion: 1,
          analysisRevision: "rev:1:dep:0",
          freshness: "current",
          completeness: "complete",
          diagnostics: [
            {
              uri: "file:///workspace/grails-app/controllers/BookController.groovy",
              severity: "Error",
              message: "Syntax error",
              range: { start: { line: 12, character: 0 }, end: { line: 12, character: 1 } },
              source: "GrailsCompiler",
            },
          ],
          totalEstimated: 1,
          limitations: [],
        } as unknown as T;

      case "grails.analysis.changeReview":
        return {
          schemaVersion: 1,
          changeId: "change-mcp-1",
          projectUri: (args[0] as { projectUri: string }).projectUri,
          analysisRevision: "rev:1:dep:0",
          freshness: "current",
          status: "ready",
          safetyVerdict: "SAFE_TO_APPLY",
          editsSummary: { totalFiles: 1, totalEdits: 1, files: ["file:///workspace/test.groovy"] },
          edits: [],
          impactSummary: { impactedCount: 1, dynamicUnknownsCount: 0, artifacts: [], dynamicUnknowns: [] },
          checksSummary: null,
          uncheckedAssumptions: [],
          recoveryActions: [],
          limitations: [],
        } as unknown as T;

      default:
        throw new Error(`Unknown command: ${command}`);
    }
  }
}

function createHarness() {
  const input = new PassThrough();
  const output = new PassThrough();
  const backend = new MockAnalysisBackend();
  const server = new GrailsMcpServer(backend, input, output);

  let outputBuffer = "";
  output.on("data", (chunk: Buffer) => {
    outputBuffer += chunk.toString("utf8");
  });

  const send = (msg: Record<string, unknown>) => {
    input.write(JSON.stringify(msg) + "\n");
  };

  const getNextResponse = async (timeoutMs = 1000): Promise<Record<string, unknown>> => {
    const startTime = Date.now();
    while (Date.now() - startTime < timeoutMs) {
      const idx = outputBuffer.indexOf("\n");
      if (idx !== -1) {
        const line = outputBuffer.slice(0, idx).trim();
        outputBuffer = outputBuffer.slice(idx + 1);
        if (line.length > 0) {
          return JSON.parse(line) as Record<string, unknown>;
        }
      }
      await new Promise((r) => setTimeout(r, 10));
    }
    throw new Error(`Timeout waiting for response. Current buffer: ${outputBuffer}`);
  };

  return { input, output, backend, server, send, getNextResponse };
}

void test("R3-04/1: McpServer exposes tools/list and executes all 4 typed analysis operations", async () => {
  const { server, send, getNextResponse, backend } = createHarness();

  try {
    // 1. Initialize
    send({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2024-11-05" } });
    const initResp = await getNextResponse();
    equal(initResp.id, 1);
    const initResult = initResp.result as Record<string, unknown>;
    equal(initResult.protocolVersion, "2024-11-05");
    ok(initResult.capabilities && typeof initResult.capabilities === "object");

    // 2. Initialized notification
    send({ jsonrpc: "2.0", method: "notifications/initialized" });
    equal(server.state, "READY");

    // 3. tools/list
    send({ jsonrpc: "2.0", id: 2, method: "tools/list" });
    const listResp = await getNextResponse();
    equal(listResp.id, 2);
    const listResult = listResp.result as { tools: { name: string }[] };
    equal(listResult.tools.length, 5);
    const toolNames = listResult.tools.map((t) => t.name);
    ok(toolNames.includes("grails_project_capabilities"));
    ok(toolNames.includes("grails_symbols_search"));
    ok(toolNames.includes("grails_symbols_explain"));
    ok(toolNames.includes("grails_project_diagnostics"));
    ok(toolNames.includes("grails_change_review"));

    // 4. tools/call: grails_project_capabilities
    send({
      jsonrpc: "2.0",
      id: 3,
      method: "tools/call",
      params: {
        name: "grails_project_capabilities",
        arguments: { projectUri: "file:///workspace/project" },
      },
    });
    const capResp = await getNextResponse();
    equal(capResp.id, 3);
    const capResult = capResp.result as { content: { type: string; text: string }[]; isError?: boolean };
    equal(capResult.isError, false);
    const capData = JSON.parse(capResult.content[0].text) as ProjectCapabilitiesResponse;
    equal(capData.projectName, "McpTestApp");
    equal(backend.lastCommand, "grails.analysis.projectCapabilities");

    // 5. tools/call: grails_symbols_search
    send({
      jsonrpc: "2.0",
      id: 4,
      method: "tools/call",
      params: {
        name: "grails_symbols_search",
        arguments: { projectUri: "file:///workspace/project", query: "Book" },
      },
    });
    const searchResp = await getNextResponse();
    equal(searchResp.id, 4);
    const searchResult = searchResp.result as { content: { type: string; text: string }[]; isError?: boolean };
    equal(searchResult.isError, false);
    const searchData = JSON.parse(searchResult.content[0].text) as SymbolSearchResponse;
    equal(searchData.items.length, 1);
    equal(searchData.items[0].name, "BookController");

    // 6. tools/call: grails_symbols_explain
    send({
      jsonrpc: "2.0",
      id: 5,
      method: "tools/call",
      params: {
        name: "grails_symbols_explain",
        arguments: {
          projectUri: "file:///workspace/project",
          documentUri: "file:///workspace/grails-app/domain/Book.groovy",
          symbolName: "findByTitle",
        },
      },
    });
    const explainResp = await getNextResponse();
    equal(explainResp.id, 5);
    const explainResult = explainResp.result as { content: { type: string; text: string }[]; isError?: boolean };
    equal(explainResult.isError, false);
    const explainData = JSON.parse(explainResult.content[0].text) as SymbolExplainResponse;
    ok(explainData.symbol);
    equal(explainData.symbol.name, "findByTitle");

    // 7. tools/call: grails_project_diagnostics
    send({
      jsonrpc: "2.0",
      id: 6,
      method: "tools/call",
      params: {
        name: "grails_project_diagnostics",
        arguments: { projectUri: "file:///workspace/project" },
      },
    });
    const diagResp = await getNextResponse();
    equal(diagResp.id, 6);
    const diagResult = diagResp.result as { content: { type: string; text: string }[]; isError?: boolean };
    equal(diagResult.isError, false);
    const diagData = JSON.parse(diagResult.content[0].text) as ProjectDiagnosticsResponse;
    equal(diagData.diagnostics.length, 1);
  } finally {
    server.dispose();
  }
});

void test("R3-04/2: McpServer declares diskFacts mode, caps parameters, and supports request cancellation", async () => {
  const { server, send, getNextResponse, backend } = createHarness();

  try {
    send({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2024-11-05" } });
    await getNextResponse();

    // Verify diskFacts mode labeling and parameter capping
    send({
      jsonrpc: "2.0",
      id: 2,
      method: "tools/call",
      params: {
        name: "grails_symbols_search",
        arguments: {
          projectUri: "file:///workspace/project",
          query: "Book",
          limit: 1000, // Capped to 200
          maxPayloadBytes: 1000000, // Capped to 262144
        },
      },
    });
    const resp = await getNextResponse();
    equal(resp.id, 2);
    const result = resp.result as { content: { type: string; text: string }[] };
    const data = JSON.parse(result.content[0].text) as Record<string, unknown>;

    // Verified: external clients operate in diskFacts mode and cannot see unsaved buffers
    equal(data.clientMode, "diskFacts");
    equal(data.unsavedEditorBuffersAvailable, false);

    // Verified: parameters were capped at boundary
    const sentReq = backend.lastArgs![0] as { limit: number; maxPayloadBytes: number };
    equal(sentReq.limit, 200);
    equal(sentReq.maxPayloadBytes, 262144);

    // Verify cancellation
    backend.simulateDelayMs = 200; // Slow request
    send({
      jsonrpc: "2.0",
      id: 3,
      method: "tools/call",
      params: {
        name: "grails_project_capabilities",
        arguments: { projectUri: "file:///workspace/project" },
      },
    });

    // Send cancellation notification immediately
    send({
      jsonrpc: "2.0",
      method: "notifications/cancelled",
      params: { requestId: 3 },
    });

    const cancelResp = await getNextResponse();
    equal(cancelResp.id, 3);
    const cancelResult = cancelResp.result as { content: { type: string; text: string }[]; isError?: boolean };
    equal(cancelResult.isError, true);
    const cancelData = JSON.parse(cancelResult.content[0].text) as AnalysisErrorResponse;
    equal(cancelData.code, "CANCELLED");
  } finally {
    server.dispose();
  }
});

void test("R3-04/3: McpServer enforces boundary validation, handles stale cursors, and cleans up on disconnect", async () => {
  const { server, input, send, getNextResponse, backend } = createHarness();

  try {
    send({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2024-11-05" } });
    await getNextResponse();

    // 1. Malformed JSON
    input.write("{ invalid json \n");
    const parseErr = await getNextResponse();
    ok(parseErr.error && typeof parseErr.error === "object");
    equal((parseErr.error as { code: number }).code, -32700);

    // 2. Unknown method
    send({ jsonrpc: "2.0", id: 2, method: "unknownMethod", params: {} });
    const methodErr = await getNextResponse();
    equal((methodErr.error as { code: number }).code, -32601);

    // 3. Boundary validation: non-file URI rejected
    send({
      jsonrpc: "2.0",
      id: 3,
      method: "tools/call",
      params: {
        name: "grails_project_capabilities",
        arguments: { projectUri: "http://example.com/project" },
      },
    });
    const uriErr = await getNextResponse();
    equal(uriErr.id, 3);
    const uriResult = uriErr.result as { content: { type: string; text: string }[]; isError?: boolean };
    equal(uriResult.isError, true);
    const uriData = JSON.parse(uriResult.content[0].text) as AnalysisErrorResponse;
    equal(uriData.code, "INVALID_INPUT");
    ok(uriData.error.includes("Must be a file:// URI"));

    // 4. Stale cursor handling: backend returns CURSOR_EXPIRED
    backend.returnErrorResponse = {
      schemaVersion: 1,
      error: "Pagination cursor has expired due to revision change.",
      code: "CURSOR_EXPIRED",
      details: "Expected revision rev:1:dep:0 but found rev:2:dep:0",
    };

    send({
      jsonrpc: "2.0",
      id: 4,
      method: "tools/call",
      params: {
        name: "grails_symbols_search",
        arguments: {
          projectUri: "file:///workspace/project",
          query: "Book",
          cursor: "stale_cursor_token",
        },
      },
    });
    const cursorResp = await getNextResponse();
    equal(cursorResp.id, 4);
    const cursorResult = cursorResp.result as { content: { type: string; text: string }[]; isError?: boolean };
    equal(cursorResult.isError, true);
    const cursorData = JSON.parse(cursorResult.content[0].text) as AnalysisErrorResponse;
    equal(cursorData.code, "CURSOR_EXPIRED");

    // 5. Disconnect cleanup on EOF / input close
    let closeEmitted = false;
    server.on("close", () => {
      closeEmitted = true;
    });

    input.end();
    await new Promise((r) => setTimeout(r, 50));
    equal(server.isDisposed, true);
    equal(server.state, "DISPOSED");
    equal(closeEmitted, true);
  } finally {
    server.dispose();
  }
});

void test("R3-04/2: McpServer handles graceful shutdown request with null result", async () => {
  const { server, send, getNextResponse } = createHarness();

  try {
    send({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2024-11-05" } });
    await getNextResponse();

    send({ jsonrpc: "2.0", id: 2, method: "shutdown" });
    const resp = await getNextResponse();
    equal(resp.id, 2);
    equal(resp.result, null);
    equal(server.state, "DISPOSED");
    equal(server.isDisposed, true);
  } finally {
    server.dispose();
  }
});

