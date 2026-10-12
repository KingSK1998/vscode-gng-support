import { equal, ok, rejects } from "node:assert/strict";
import { test } from "node:test";
import { AnalysisClientError, AnalysisToolService } from "../services/analysis/AnalysisToolService";
import type { ErrorService } from "../services/errors/ErrorService";
import type { LanguageServerManager } from "../services/languageServer/LanguageServerManager";
import type { ConfigurationService } from "../services/workspace/ConfigurationService";
import type { ChangeImpactRequest, ChangeImpactResponse } from "../shared/protocol/analysis";

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

void test("R5-01/1: getChangeImpact validates arguments and returns impact analysis", async () => {
  let capturedCommand = "";
  let capturedArgs: ChangeImpactRequest | undefined;

  const lspManager = createMockLspManager(true, (method, params) => {
    if (method === "workspace/executeCommand") {
      const p = params as { command: string; arguments: [ChangeImpactRequest] };
      capturedCommand = p.command;
      capturedArgs = p.arguments[0];
      const resp: ChangeImpactResponse = {
        schemaVersion: 1,
        projectUri: capturedArgs.projectUri,
        analysisRevision: "rev:1:dep:0",
        freshness: "current",
        completeness: "complete",
        changedUris: capturedArgs.changedUris,
        impactedArtifacts: [],
        dynamicUnknowns: [],
        verificationSuggestions: [],
        totalEstimated: 0,
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

  // 1. Validation error on empty changedUris
  await rejects(
    async () => {
      await service.getChangeImpact({
        projectUri: "file:///workspace/project",
        changedUris: [],
      });
    },
    (err: unknown) => {
      ok(err instanceof AnalysisClientError);
      equal(err.code, "INVALID_INPUT");
      return true;
    }
  );

  // 2. Successful call with bounded depth and limit
  const resp = await service.getChangeImpact({
    projectUri: "file:///workspace/project",
    changedUris: ["file:///workspace/project/grails-app/domain/Book.groovy"],
    depth: 10, // should clamp to 5
    limit: 1000, // should clamp to 500
  });

  equal(capturedCommand, "grails.analysis.changeImpact");
  ok(capturedArgs);
  equal(capturedArgs.depth, 5);
  equal(capturedArgs.limit, 500);
  equal(resp.schemaVersion, 1);
});
