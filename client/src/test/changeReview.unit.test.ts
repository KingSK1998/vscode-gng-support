import { equal, ok, rejects } from "node:assert/strict";
import { test } from "node:test";
import { AnalysisClientError, AnalysisToolService } from "../services/analysis/AnalysisToolService";
import type { ErrorService } from "../services/errors/ErrorService";
import type { LanguageServerManager } from "../services/languageServer/LanguageServerManager";
import type { ConfigurationService } from "../services/workspace/ConfigurationService";
import type {
  ChangeReviewRequest,
  ChangeReviewResponse,
} from "../shared/protocol/analysis";
import { ChangeReviewUseCase } from "../ui/commands/AnalysisCommands";
import { ChangeReviewPresenter } from "../ui/views/ChangeReviewPresenter";

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
      // Intentionally empty for mock
    },
  } as unknown as ErrorService;
}

function createMockConfigService(): ConfigurationService {
  return {} as unknown as ConfigurationService;
}

const sampleReviewResponse: ChangeReviewResponse = {
  schemaVersion: 1,
  changeId: "change-101",
  projectUri: "file:///workspace/demo-app",
  analysisRevision: "rev:42:dep:3",
  freshness: "current",
  status: "ready",
  safetyVerdict: "SAFE_TO_APPLY",
  editsSummary: {
    totalFiles: 1,
    totalEdits: 1,
    hasCollisions: false,
    files: ["file:///workspace/demo-app/grails-app/controllers/BookController.groovy"],
  },
  edits: [
    {
      uri: "file:///workspace/demo-app/grails-app/controllers/BookController.groovy",
      documentVersion: 1,
      range: { start: { line: 0, character: 6 }, end: { line: 0, character: 20 } },
      newText: "VolumeController",
      evidence: "Declaration match",
      isEmbeddedGsp: false,
    },
  ],
  impactSummary: {
    impactedCount: 1,
    dynamicUnknownsCount: 0,
    artifacts: ["file:///workspace/demo-app/grails-app/controllers/BookController.groovy"],
    dynamicUnknowns: [],
  },
  checksSummary: {
    checkStatus: "success",
    testsTotal: 10,
    testsPassed: 10,
    testsFailed: 0,
    testsSkipped: 0,
    compilationSuccess: true,
    errorCount: 0,
    warningCount: 0,
  },
  uncheckedAssumptions: ["Standard Grails naming convention applies"],
  recoveryActions: [],
  limitations: [
    "Review connects declared edits and evidence. Generated prose alone does not constitute approval.",
  ],
};

void test("R5-04/1: ChangeReviewPresenter formats comprehensive markdown", () => {
  const md = ChangeReviewPresenter.formatMarkdown(sampleReviewResponse);

  ok(md.includes("Change Review: `change-101`"));
  ok(md.includes("rev:42:dep:3"));
  ok(md.includes("SAFE_TO_APPLY"));
  ok(md.includes("Total Files Affected**: 1"));
  ok(md.includes("BookController.groovy"));
  ok(md.includes("10 passed / 0 failed"));
  ok(md.includes("Standard Grails naming convention applies"));
  ok(md.includes("Generated prose alone does not constitute approval"));
});

void test("R5-04/2: ChangeReviewPresenter renders accessible HTML with ARIA and theme tokens", () => {
  const html = ChangeReviewPresenter.renderHtml(sampleReviewResponse);

  ok(html.includes("role=\"region\""));
  ok(html.includes("role=\"status\""));
  ok(html.includes("aria-label=\"Safety Verdict\""));
  ok(html.includes("var(--vscode-editor-foreground"));
  ok(html.includes("SAFE_TO_APPLY"));
  ok(html.includes("BookController.groovy"));
});

void test("R5-04/3: Warning and error banners render for stale buffer and failed checks", () => {
  const staleResponse: ChangeReviewResponse = {
    ...sampleReviewResponse,
    status: "stale_buffer",
    safetyVerdict: "STALE_REVIEW",
    freshness: "invalidated",
    recoveryActions: ["Buffer version advanced from v1 to v2. Recompute rename preview."],
  };

  const md = ChangeReviewPresenter.formatMarkdown(staleResponse);
  ok(md.includes("STALE_BUFFER"));
  ok(md.includes("Buffer version advanced from v1 to v2"));

  const html = ChangeReviewPresenter.renderHtml(staleResponse);
  ok(html.includes("banner-warning"));
  ok(html.includes("Buffer version advanced from v1 to v2"));

  const failedChecksResponse: ChangeReviewResponse = {
    ...sampleReviewResponse,
    status: "checks_failed",
    safetyVerdict: "UNSAFE_TO_APPLY",
    recoveryActions: ["Resolve 2 compilation errors before applying."],
  };

  const failedHtml = ChangeReviewPresenter.renderHtml(failedChecksResponse);
  ok(failedHtml.includes("banner-error"));
  ok(failedHtml.includes("Resolve 2 compilation errors before applying."));
});

void test("R5-04/4: AnalysisToolService.getChangeReview sends LSP command and returns response", async () => {
  let capturedCommand = "";
  let capturedArgs: unknown = null;

  const lspManager = createMockLspManager(true, (method, params) => {
    capturedCommand = (params as { command: string }).command;
    capturedArgs = (params as { arguments: unknown[] }).arguments[0];
    return Promise.resolve(sampleReviewResponse);
  });

  const service = new AnalysisToolService(
    lspManager,
    createMockErrorService(),
    createMockConfigService()
  );

  const req: ChangeReviewRequest = {
    projectUri: "file:///workspace/demo-app",
    changeId: "change-101",
    edits: sampleReviewResponse.edits,
  };

  const resp = await service.getChangeReview(req);

  equal(capturedCommand, "grails.analysis.changeReview");
  ok(capturedArgs !== null);
  equal(resp.changeId, "change-101");
  equal(resp.safetyVerdict, "SAFE_TO_APPLY");
  equal(resp.status, "ready");
});

void test("R5-04/5: ChangeReviewUseCase handles successful execution and errors", async () => {
  const lspManager = createMockLspManager(true, () => Promise.resolve(sampleReviewResponse));
  const service = new AnalysisToolService(
    lspManager,
    createMockErrorService(),
    createMockConfigService()
  );

  const useCase = new ChangeReviewUseCase(service, createMockErrorService());
  const result = await useCase.execute({
    projectUri: "file:///workspace/demo-app",
  });

  ok(result !== undefined);
  equal(result?.changeId, "change-101");

  // Error case: server not running
  const stoppedLspManager = createMockLspManager(false);
  const stoppedService = new AnalysisToolService(
    stoppedLspManager,
    createMockErrorService(),
    createMockConfigService()
  );
  const failingUseCase = new ChangeReviewUseCase(stoppedService, createMockErrorService());
  const errorResult = await failingUseCase.execute({
    projectUri: "file:///workspace/demo-app",
  });

  equal(errorResult, undefined);
});

void test("R5-04/6: AnalysisToolService.getChangeReview rejects invalid or null input", async () => {
  const service = new AnalysisToolService(
    createMockLspManager(true),
    createMockErrorService(),
    createMockConfigService()
  );

  await rejects(
    async () => {
      // @ts-expect-error Testing runtime handling of null input
      await service.getChangeReview(null);
    },
    (err: unknown) => {
      ok(err instanceof AnalysisClientError);
      equal(err.code, "INVALID_INPUT");
      return true;
    }
  );
});
