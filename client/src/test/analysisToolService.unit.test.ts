import { equal, ok, rejects } from "node:assert/strict";
import { test } from "node:test";
import * as vscode from "vscode";
import { AnalysisClientError, AnalysisToolService } from "../services/analysis/AnalysisToolService";
import type { ErrorService } from "../services/errors/ErrorService";
import type { LanguageServerManager } from "../services/languageServer/LanguageServerManager";
import type { ConfigurationService } from "../services/workspace/ConfigurationService";

// Helper to create mock LanguageServerManager
function createMockLspManager(
  isRunning = true,
  onRequest?: (method: string, params: unknown) => Promise<unknown>
): LanguageServerManager {
  return {
    isRunning,
    languageClient: isRunning
      ? ({
          sendRequest: async (method: string, params: unknown) => {
            if (onRequest) {
              return await onRequest(method, params);
            }
            return {};
          },
        } as unknown as LanguageServerManager["languageClient"])
      : undefined,
  } as unknown as LanguageServerManager;
}

// Helper to create mock ErrorService
function createMockErrorService(): ErrorService {
  return {
    handleError: () => {
      // Intentionally empty for mock
    },
  } as unknown as ErrorService;
}

// Helper to create mock ConfigurationService
function createMockConfigService(): ConfigurationService {
  return {} as unknown as ConfigurationService;
}

void test("R3-02/1: Bounded parameters enforce defensive caps on limit and payload bytes", async () => {
  let capturedArgs: unknown;
  const lspManager = createMockLspManager(true, (_method, params) => {
    capturedArgs = params;
    return Promise.resolve({
      schemaVersion: 1,
      projectUri: "file:///workspace/project",
      analysisRevision: "rev:1:dep:0",
      freshness: "current",
      completeness: "complete",
      items: [],
      totalEstimated: 0,
      limitations: [],
    });
  });

  const service = new AnalysisToolService(
    lspManager,
    createMockErrorService(),
    createMockConfigService()
  );

  // Configure workspace stub
  const originalIsTrusted = vscode.workspace.isTrusted;
  const originalWorkspaceFolders = vscode.workspace.workspaceFolders;
  try {
    Object.defineProperty(vscode.workspace, "isTrusted", { value: true, configurable: true });
    Object.defineProperty(vscode.workspace, "workspaceFolders", {
      value: [{ uri: vscode.Uri.parse("file:///workspace/project") }],
      configurable: true,
    });

    const resp = await service.searchSymbols({
      projectUri: "file:///workspace/project",
      query: "TestController",
      limit: 1000, // Exceeds max 200
      maxPayloadBytes: 1000000, // Exceeds max 262144
    });

    equal(resp.schemaVersion, 1);
    ok(capturedArgs && typeof capturedArgs === "object");
    const sentParams = capturedArgs as { command: string; arguments: [Record<string, unknown>] };
    equal(sentParams.command, "grails.analysis.symbolSearch");
    equal(sentParams.arguments[0].limit, 200); // Capped to 200
    equal(sentParams.arguments[0].maxPayloadBytes, 262144); // Capped to 262144
  } finally {
    Object.defineProperty(vscode.workspace, "isTrusted", { value: originalIsTrusted, configurable: true });
    Object.defineProperty(vscode.workspace, "workspaceFolders", {
      value: originalWorkspaceFolders,
      configurable: true,
    });
    service.dispose();
  }
});

void test("R3-02/2: Untrusted workspace blocks analysis reads with explicit UNAVAILABLE error", async () => {
  const service = new AnalysisToolService(
    createMockLspManager(true),
    createMockErrorService(),
    createMockConfigService()
  );

  const originalIsTrusted = vscode.workspace.isTrusted;
  try {
    Object.defineProperty(vscode.workspace, "isTrusted", { value: false, configurable: true });

    await rejects(
      async () => {
        await service.getProjectCapabilities({ projectUri: "file:///workspace/project" });
      },
      (err: unknown) => {
        ok(err instanceof AnalysisClientError);
        equal(err.code, "UNAVAILABLE");
        ok(err.message.includes("untrusted"));
        return true;
      }
    );
  } finally {
    Object.defineProperty(vscode.workspace, "isTrusted", { value: originalIsTrusted, configurable: true });
    service.dispose();
  }
});

void test("R3-02/2: Target URI outside workspace bounds rejected with INVALID_INPUT error", async () => {
  const service = new AnalysisToolService(
    createMockLspManager(true),
    createMockErrorService(),
    createMockConfigService()
  );

  const originalIsTrusted = vscode.workspace.isTrusted;
  const originalWorkspaceFolders = vscode.workspace.workspaceFolders;
  try {
    Object.defineProperty(vscode.workspace, "isTrusted", { value: true, configurable: true });
    Object.defineProperty(vscode.workspace, "workspaceFolders", {
      value: [{ uri: vscode.Uri.parse("file:///workspace/project") }],
      configurable: true,
    });

    await rejects(
      async () => {
        await service.getProjectCapabilities({ projectUri: "file:///outside/path" });
      },
      (err: unknown) => {
        ok(err instanceof AnalysisClientError);
        equal(err.code, "INVALID_INPUT");
        ok(err.message.includes("outside"));
        return true;
      }
    );
  } finally {
    Object.defineProperty(vscode.workspace, "isTrusted", { value: originalIsTrusted, configurable: true });
    Object.defineProperty(vscode.workspace, "workspaceFolders", {
      value: originalWorkspaceFolders,
      configurable: true,
    });
    service.dispose();
  }
});

void test("R3-02/2: Server errors map to AnalysisClientError with server error code and details", async () => {
  const lspManager = createMockLspManager(true, () => {
    return Promise.resolve({
      schemaVersion: 1,
      error: "Project state is currently hibernated",
      code: "UNAVAILABLE",
      details: "ProjectContext.State: HIBERNATED",
    });
  });

  const service = new AnalysisToolService(
    lspManager,
    createMockErrorService(),
    createMockConfigService()
  );

  const originalIsTrusted = vscode.workspace.isTrusted;
  const originalWorkspaceFolders = vscode.workspace.workspaceFolders;
  try {
    Object.defineProperty(vscode.workspace, "isTrusted", { value: true, configurable: true });
    Object.defineProperty(vscode.workspace, "workspaceFolders", {
      value: [{ uri: vscode.Uri.parse("file:///workspace/project") }],
      configurable: true,
    });

    await rejects(
      async () => {
        await service.getProjectCapabilities({ projectUri: "file:///workspace/project" });
      },
      (err: unknown) => {
        ok(err instanceof AnalysisClientError);
        equal(err.code, "UNAVAILABLE");
        equal(err.message, "Project state is currently hibernated");
        equal(err.details, "ProjectContext.State: HIBERNATED");
        return true;
      }
    );
  } finally {
    Object.defineProperty(vscode.workspace, "isTrusted", { value: originalIsTrusted, configurable: true });
    Object.defineProperty(vscode.workspace, "workspaceFolders", {
      value: originalWorkspaceFolders,
      configurable: true,
    });
    service.dispose();
  }
});

void test("R3-02/3: Disposed service rejects requests with UNAVAILABLE error", async () => {
  const service = new AnalysisToolService(
    createMockLspManager(true),
    createMockErrorService(),
    createMockConfigService()
  );

  equal(service.isDisposed, false);
  service.dispose();
  equal(service.isDisposed, true);

  await rejects(
    async () => {
      await service.getProjectCapabilities({ projectUri: "file:///workspace/project" });
    },
    (err: unknown) => {
      ok(err instanceof AnalysisClientError);
      equal(err.code, "UNAVAILABLE");
      ok(err.message.includes("disposed"));
      return true;
    }
  );
});

void test("R3-02/1: Language model tools registration and disposal lifecycle", () => {
  const registeredTools = new Map<string, unknown>();

  const originalRegisterTool = vscode.lm?.registerTool;
  (vscode.lm as unknown as { registerTool: (name: string, tool: unknown) => vscode.Disposable }).registerTool = (
    name: string,
    tool: unknown
  ) => {
    registeredTools.set(name, tool);
    return {
      dispose: () => {
        registeredTools.delete(name);
      },
    };
  };

  const service = new AnalysisToolService(
    createMockLspManager(true),
    createMockErrorService(),
    createMockConfigService()
  );

  try {
    const disposable = service.registerLanguageModelTools();
    equal(registeredTools.size, 9);
    ok(registeredTools.has("grails_project_capabilities"));
    ok(registeredTools.has("grails_symbols_search"));
    ok(registeredTools.has("grails_symbols_explain"));
    ok(registeredTools.has("grails_project_diagnostics"));
    ok(registeredTools.has("grails_relationships"));
    ok(registeredTools.has("grails_change_impact"));
    ok(registeredTools.has("grails_refactor_preview"));
    ok(registeredTools.has("grails_checks_run"));
    ok(registeredTools.has("grails_change_review"));

    disposable.dispose();
    equal(registeredTools.size, 0);
  } finally {
    if (originalRegisterTool) {
      (vscode.lm as unknown as { registerTool: unknown }).registerTool = originalRegisterTool;
    }
    service.dispose();
  }
});

void test("R3-02/2: Null or non-object request payload rejected with INVALID_INPUT error", async () => {
  const service = new AnalysisToolService(
    createMockLspManager(true),
    createMockErrorService(),
    createMockConfigService()
  );

  await rejects(
    async () => {
      // @ts-expect-error Testing runtime handling of null input
      await service.getProjectCapabilities(null);
    },
    (err: unknown) => {
      ok(err instanceof AnalysisClientError);
      equal(err.code, "INVALID_INPUT");
      equal(err.message, "Request payload is required and must be an object.");
      return true;
    }
  );
});

