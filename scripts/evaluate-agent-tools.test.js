const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const path = require("path");
const {
  loadBenchmark,
  runEvaluation,
  generateMarkdownFindings,
} = require("./evaluate-agent-tools");

test("R3-03/1: Benchmark dataset contains valid, versioned tasks across required categories", () => {
  const benchmark = loadBenchmark();

  assert.equal(benchmark.version, 1);
  assert.ok(benchmark.taskCount >= 20, "Should have at least 20 evaluation tasks");
  assert.equal(benchmark.tasks.length, benchmark.taskCount);

  const categories = new Set(benchmark.tasks.map((t) => t.category));
  assert.ok(categories.has("wrong_version_api"), "Must cover wrong_version_api");
  assert.ok(categories.has("grails_relationships"), "Must cover grails_relationships");
  assert.ok(categories.has("diagnosis"), "Must cover diagnosis");
  assert.ok(categories.has("held_out"), "Must cover held_out");

  const heldOutTasks = benchmark.tasks.filter((t) => t.heldOut);
  assert.ok(heldOutTasks.length >= 4, "Must contain held-out evaluation tasks");

  for (const t of benchmark.tasks) {
    assert.ok(t.id, "Task must have an ID");
    assert.ok(t.name, `Task ${t.id} must have a name`);
    assert.ok(t.queryPrompt, `Task ${t.id} must have a queryPrompt`);
    assert.ok(t.expectedResolution, `Task ${t.id} must define expectedResolution`);
    assert.ok(t.negativeChecks, `Task ${t.id} must define negativeChecks`);
  }
});

test("R3-03/2: Evaluation harness compares search_build_only vs analysis_tools and measures improvements", () => {
  const summary = runEvaluation(3);

  assert.ok(summary.benchmarkVersion === 1);
  assert.ok(summary.taskCount >= 20);

  const searchStats = summary.strategies.search_build_only.overall;
  const toolStats = summary.strategies.analysis_tools.overall;

  // Analysis tools must show higher success rate
  assert.ok(
    toolStats.successRatePct > searchStats.successRatePct,
    `Analysis tools success rate (${toolStats.successRatePct}%) should exceed search/build (${searchStats.successRatePct}%)`
  );

  // Analysis tools must show lower error/hallucination rate
  assert.ok(
    toolStats.errorRatePct < searchStats.errorRatePct,
    `Analysis tools error rate (${toolStats.errorRatePct}%) should be lower than search/build (${searchStats.errorRatePct}%)`
  );

  // Token consumption must be significantly reduced
  assert.ok(
    summary.comparison.tokenReductionRatio > 1.5,
    `Token reduction ratio (${summary.comparison.tokenReductionRatio}x) should be > 1.5x`
  );

  // Report files must be generated
  const reportPath = path.resolve(__dirname, "../reports/agent-eval-report.json");
  assert.ok(fs.existsSync(reportPath), "agent-eval-report.json must be generated");

  generateMarkdownFindings(summary);
  const findingsPath = path.resolve(__dirname, "../docs/reports/agent-eval-findings.md");
  assert.ok(fs.existsSync(findingsPath), "agent-eval-findings.md must be generated");
});

test("R3-03/3: Evaluation identifies and documents concrete limitations and roadmap recommendations", () => {
  const summary = runEvaluation(1);

  assert.ok(Array.isArray(summary.limitations), "Must publish limitations array");
  assert.ok(summary.limitations.length >= 2, "Must identify at least two concrete limitations");

  for (const lim of summary.limitations) {
    assert.ok(lim.id, "Limitation must have an id");
    assert.ok(lim.description, "Limitation must have a description");
    assert.ok(lim.recommendation, "Limitation must have an actionable recommendation");
  }
});

