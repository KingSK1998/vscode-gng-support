import { equal, ok, rejects } from "node:assert/strict";
import { test } from "node:test";
import { AnalysisClientError, AnalysisToolService } from "../services/analysis/AnalysisToolService";
import type { ErrorService } from "../services/errors/ErrorService";
import type { LanguageServerManager } from "../services/languageServer/LanguageServerManager";
import type { ConfigurationService } from "../services/workspace/ConfigurationService";
import type { RenamePreviewRequest, RenamePreviewResponse, RenameApplyRequest, RenameApplyResponse } from "../shared/protocol/analysis";

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

function createMockErrorService(): ErrorService {
  return {
    handleError: () => {
      // no-op mock
    },
  } as unknown as ErrorService;
}

function createMockConfigService(): ConfigurationService {
  return {} as unknown as ConfigurationService;
}

void test("R5-02/1: getRenamePreview validates arguments and returns preview", async () => {
  let capturedCommand = "";
  let capturedArgs: RenamePreviewRequest | undefined;

  const lspManager = createMockLspManager(true, (method, params) => {
    if (method === "workspace/executeCommand") {
      const p = params as { command: string; arguments: [RenamePreviewRequest] };
      capturedCommand = p.command;
      capturedArgs = p.arguments[0];
      const resp: RenamePreviewResponse = {
        schemaVersion: 1,
        previewId: "prev-123",
        projectUri: capturedArgs.projectUri,
        analysisRevision: "rev:1:dep:0",
        freshness: "current",
        targetSymbol: {
          name: "oldName",
          kind: "Property",
          uri: capturedArgs.documentUri,
          range: { start: { line: 1, character: 0 }, end: { line: 1, character: 7 } },
        },
        newName: capturedArgs.newName,
        canApply: true,
        edits: [
          {
            uri: capturedArgs.documentUri,
            range: { start: { line: 1, character: 0 }, end: { line: 1, character: 7 } },
            newText: capturedArgs.newName,
          },
        ],
        documentPreconditions: [{ uri: capturedArgs.documentUri, expectedVersion: 1 }],
        uncertainMatches: [],
        collisions: [],
        limitations: [],
      };
      return Promise.resolve(resp);
    }
    return Promise.resolve({});
  });

  const service = new AnalysisToolService(
    lspManager,
    createMockErrorService(),
    createMockConfigService()
  );

  // 1. Validation error on missing newName
  await rejects(
    async () => {
      await service.getRenamePreview({
        projectUri: "file:///workspace/project",
        documentUri: "file:///workspace/project/grails-app/domain/Book.groovy",
        position: { line: 1, character: 0 },
        newName: "",
      });
    },
    (err: unknown) => {
      ok(err instanceof AnalysisClientError);
      equal(err.code, "INVALID_INPUT");
      return true;
    }
  );

  // 2. Successful preview
  const preview = await service.getRenamePreview({
    projectUri: "file:///workspace/project",
    documentUri: "file:///workspace/project/grails-app/domain/Book.groovy",
    position: { line: 1, character: 0 },
    newName: "updatedName",
  });

  equal(capturedCommand, "grails.refactor.preview");
  ok(capturedArgs);
  equal(preview.canApply, true);
  equal(preview.edits.length, 1);
  equal(preview.newName, "updatedName");
});

void test("R5-02/2: applyRename validates arguments and dispatches apply", async () => {
  let capturedCommand = "";
  let capturedArgs: RenameApplyRequest | undefined;

  const lspManager = createMockLspManager(true, (method, params) => {
    if (method === "workspace/executeCommand") {
      const p = params as { command: string; arguments: [RenameApplyRequest] };
      capturedCommand = p.command;
      capturedArgs = p.arguments[0];
      const resp: RenameApplyResponse = {
        schemaVersion: 1,
        applied: true,
        appliedEditsCount: 1,
        rejectedEditsCount: 0,
      };
      return Promise.resolve(resp);
    }
    return Promise.resolve({});
  });

  const service = new AnalysisToolService(
    lspManager,
    createMockErrorService(),
    createMockConfigService()
  );

  const applyResp = await service.applyRename({
    previewId: "prev-123",
    projectUri: "file:///workspace/project",
    documentPreconditions: [],
    edits: [
      {
        uri: "file:///workspace/project/grails-app/domain/Book.groovy",
        range: { start: { line: 1, character: 0 }, end: { line: 1, character: 7 } },
        newText: "updatedName",
      },
    ],
  });

  equal(capturedCommand, "grails.refactor.apply");
  ok(capturedArgs);
  equal(applyResp.applied, true);
  equal(applyResp.appliedEditsCount, 1);
});
