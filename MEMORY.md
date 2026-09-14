# Session Memory — Agent Optimization

## Decisions Made & Patterns Established

[... same as previous ...]

9. **Multi-Project & Lifecycle Architecture (Phase 3)**:
   - Established **Single Source of Truth** per request via `VersionedSnapshot`. All LSP handlers are now bound to an immutable snapshot of the workspace (AST, Index, Gradle Metadata) at request entry, preventing state-drift mid-calculation.
   - Implemented **Compilation Commit Protocol**: Compilers now work on private state and atomically "commit" new snapshots only after validation. This decouples long-running index rebuilds from instant-response LSP lookups.
   - **WorkspaceManager Isolation**: Replaced monolithic maps in `GrailsService` with a proper workspace router. Each project root owns its own `ProjectContextImpl`, ensuring strict isolation between multiple workspace folders.
   - **Stateless Completion Engine**: Completion strategies are now completely stateless, returning `List<CompletionItem>` instead of mutating a request object. Reflection-based strategy loading was rejected in favor of explicit wiring in `CompletionBuilder` for transparency and type safety.
   - **Memory Lifecycle (Hibernation)**: Projects now support a `hibernate()` state where compiler and visitor caches are explicitly dropped to free up memory when projects are removed or system resources are low.

10. **Agentic Knowledge Base System (KB)**:
    - Established an **Agent-Readable Knowledge Base** to move from "vibe coding" to structured agentic engineering.
    - Created **`docs/invariants.md`** defining explicit state ownership, ID contracts (durable vs mutable), state consistency rules with severity ratings (CRITICAL/HIGH/MEDIUM), and an invariant review checklist.
    - Established **`docs/failure-modes.md`** as a Failure Mode Registry indexing past bugs by component and invariant rule with a Status lifecycle (Active/Resolved/Historical) and a 9-category Failure Test Matrix.
    - Documented active and past architectural decisions in **`docs/adr/decisions.md`** with status lifecycles (Proposed/Accepted/Superseded/Deprecated).
    - Created **`docs/architecture/system-map.md`** as the first file read by new agents to understand data flow, state ownership, provider tiers, and architectural risk zones.
    - Defined a clear maintenance protocol in **`docs/architecture/change-triggers.md`** to list required KB updates per change.
    - Backlogged hypothetical design proposals in **`docs/architecture/backlog.md`** (e.g. `VersionedSnapshot`) until empirical evidence or benchmarks justify promotion.
    - Automated drift prevention by updating **`scripts/pre-commit-guard.js`** to warn on missing KB updates (e.g. `GrailsService` changes without `invariants.md` edits, new caches/providers, bug-fix commit messages without `failure-modes.md` updates).
    - Embedded the **Invariant Review Checklist** and KB update checks directly into the agent-facing **`docs/skills/review.md`** skill.

11. **Project reactivation & concurrency locking**: Integrated thread-safe reactivation lock via `activationFuture` and `ensureActivated()` in `ProjectContextImpl` to prevent duplicate compiler instantiation under concurrent requests.
12. **LRU Memory budgeting and eviction**: Added automatic memory eviction to `WorkspaceManager` that keeps at most 2 active projects READY, evicting the least recently used to HIBERNATED state. Wired the check to trigger both on workspace access and when project states transition to READY.
13. **Snapshot manager lineage tracking**: Created `SnapshotManager` to maintain versioned snapshot lineage with a maximum of 3 historical records. Used SoftReferences to hold history so the JVM can reclaim them under memory pressure, while guaranteeing LKG is preserved on compilation/sync failures.
14. **Groovy Direct Field Access in Lifecycle Methods**: Used `this.@compiler` and
    `this.@visitor` inside `hibernate()` and `dispose()` as a consistency discipline —
    bypasses Groovy's property dispatch unconditionally, protecting lock-guarded
    nullification from any future getter side effects introduced in subclasses or refactors.

## Previous Session Priorities (superseded by the 2026-09-08 handoff below)

1. **Gradle Sync Audit & Debouncing (Phase 3c.1)**:
   - Implement 2-second debounce on build.gradle watches, a 30-second timeout, and proper connection pool cancellation.
2. **Memory Escalation Path (Phase 3c.2)**:
   - Implement 3-stage OOM escalation path (Tier 2 -> Global Hibernation -> Rejected Operations) and cool-down duration exit criteria.
3. **Dependency Invalidation — Gradle Integration (Phase 3b.3)**:
   - Integrate actual Gradle dependency graph resolution into `projectDependsOn()`.
   - Test with real multi-project Gradle workspace.
4. **Integration Test Suite**:
   - Write integration tests for reactivation under concurrent edits, dependency propagation, and Gradle timeouts.

## Decisions Made & Patterns Established (2026-06-24)

15. **Dependency Graph Invalidation Tests (Phase 3b.3)**: Created `DependencyInvalidationSpec` with 6 comprehensive tests covering:
    - Dependency detection by name match
    - Dependency detection by jar classpath containment
    - Independent project isolation (no false positives)
    - Dirty flag propagation on upstream snapshot commit
    - Cycle protection in circular dependency graphs (A->B->C->A)
    - All tests passing — validates `WorkspaceManager.propagateInvalidation()` BFS traversal with visited-set cycle detection.

## Product direction and interrupted implementation handoff (2026-09-08)

- The user confirmed **Grails 7+ / Groovy 4 first**, then explicitly requested a complete roadmap and handoff because usage limits interrupted coding. The latest task is documentation/strategy; runtime work remains unfinished.
- [docs/product-roadmap.md](docs/product-roadmap.md) now defines the intended 1.0 product, human and agent workflows, project-derived framework discovery, performance/storage targets, UI direction and task IDs R0–R6. These are plans, not implementation claims.
- Strategic direction: maintain one local analysis engine used by the IDE and agent tools. Provide installed-version API evidence, Grails relationships, conservative change impact and reproducible checks. Keep deterministic IDE features useful without a model or hosted account. Evaluate agent benefits against the same agent with ordinary search/build access; future relevance is a hypothesis to measure.
- Introduce useful editor agent reads in R3 after query correctness, without waiting for every UI/refactoring feature. Add MCP portability when typed operations stabilize. No custom completion-model training or mandatory chat/cloud layer is planned for 1.0.
- [docs/implementation-handoff.md](docs/implementation-handoff.md) is the execution entry point. **Next task: R0-01**, repair interrupted document scheduling/project publication and pass the affected tests before expanding scope.
- Current saved reports: `GrailsIncrementalCompilerSpec` has 7 tests / 7 failures (`ProjectState` cast to `AtomicReference`); `DocumentCompilationSpec` has 6 tests / 3 failures (map `.empty` assertions). Reports were read on 2026-09-08, not rerun. The earlier passing compiler baseline predates these changes.
- Client startup/build changes and five passing headless tests exist from the preceding session. Real JVM smoke reproduced stdout logging corruption; stderr source fix exists but final JAR/VSIX validation remains pending.
- Initial workspace discovery remains a TODO, folder add/remove remains incomplete, and the startup agent stopped at a usage limit before implementation. Preserve all existing edits. No commit/push/publish was performed.
- Corrections to older memory: current compiler code combines workspace classpaths, and snapshot map copies do not prove deep AST immutability or classloader release. Earlier broad “strict isolation”/“complete” claims require revalidation; do not use them as acceptance evidence.

## Agent execution contracts and specifications (2026-09-08)

- The user requested actionable documentation/specs so other agents can continue from the roadmap and preserve invariants. This session delivered documentation plus two Node execution-tool files; it did not repair or revalidate client/server runtime behavior.
- [docs/agent-execution.md](docs/agent-execution.md) is now the continuing-work entry point. The roadmap, README and AGENTS point there. Run `node scripts/roadmap.js next`; current selection is **R0-01**, queued/unassigned with existing partial code. The earlier implementation handoff is baseline evidence, not a permanently fixed next-task instruction.
- [docs/execution/task-queue.json](docs/execution/task-queue.json) is the single acceptance-status/dependency ledger. All 38 roadmap IDs have [acceptance cards](docs/execution/task-specifications.md); R0-R5 are admitted, R6 deferred. Cards contain numbered cases, source starting points, spec links and invariant mappings. Records preserve evidence and interruption recovery. Reopening a task also requeues accepted/active transitive dependents for reverification.
- Added specs for library discovery, performance, agent operations, IDE/embedded workflows and validation; replaced the old state/lifecycle proposal with an explicit required contract and current evidence gaps. Fixed grammar keywords remain legitimate; framework/library API inventories need resolved-artifact evidence, capability guards and negative removal/isolation tests.
- ADR-009 reconciles GrailsService composition with per-project ownership; ADR-004/005 retain historical clauses marked superseded. Actual provider constructors take ProviderContext/WorkspaceManager and capture RequestContext; do not copy obsolete three-context wiring. ADR-008 remains reserved for a withdrawn snapshot proposal.
- Retention must preserve usable active/LKG facts while releasing compiler/classloader generations after bounded request leases drain. Shallow AST copies, live field nullification and SoftReference alone are not proof. R1-04 must select/prove the concrete mechanism; no automatic deep-AST-copy rewrite is directed.
- Query freshness and completeness are separate. Project/source-set/document/open/dependency/config revisions constrain results; unknown dynamic behavior never becomes certain refactor evidence. New agent adapters reuse typed operations rather than another index.
- Reconciled system map, architecture overview, invariants, rules, review/performance/planning/recovery skills and old reasoning-guide status. Planning/diagnostic stages return to authorized implementation; routine fixes do not require repetitive permission requests.
- Verification: selector focused tests **12/12 passed**, actual queue validates **38 tasks**, and `next` selects R0-01. Direct Node test invocation worked after `node --test` child-worker spawn EPERM. Link/anchor and independent contract review evidence is in [docs/execution/documentation-baseline.md](docs/execution/documentation-baseline.md). No commit, push, publish or new product build/test was performed for this documentation session.

## R0-01 and R0-02 Execution Milestone (2026-09-09)

- **R0-01 Completed & Accepted**:
  - Repaired document compilation scheduler: nonblocking open, rapid edit coalescing, copied buffer immutability, blocked worker ordering.
  - Resolved `ProjectState` to `AtomicReference` runtime cast error under `@CompileStatic` by using direct field accessor `this.@state.get()`.
  - All 9/9 tests in `DocumentCompilationSpec` and 7/7 tests in `GrailsIncrementalCompilerSpec` passed.
  - Documented in `docs/execution/records/R0-01.md`.

- **R0-02 Completed & Accepted**:
  - Implemented prompt capability return on `initialize` supporting 0 and multiple workspace roots without blocking on Gradle.
  - Implemented asynchronous discovery triggering on `initialized` LSP notification via `WorkspaceManager.initializeRoots()`.
  - Implemented root generations in `WorkspaceManager` with in-flight discovery tracking to discard late results from removed or re-added roots.
  - Enhanced path normalization (`TextFile.normalizePath`) to handle Windows backslashes, percent-encoded URIs, and root boundary containment (`isSameOrChildPath`).
  - Enforced strict project routing in `WorkspaceManager.getProjectForUri`: returns `null` for unmatched external URIs without falling back to a default project.
  - Implemented `replayStillOpenBuffers` on newly registered roots, safely filtering to only still-open buffers belonging to that root.
  - Updated build-watch debounce in `GrailsWorkspaceService` and `ActivationManager.ts` to cover Kotlin scripts (`build.gradle.kts`, `settings.gradle.kts`), properties (`gradle.properties`), and version catalogs (`*.versions.toml`), unioning all changed URIs across debounce windows.
  - Designed resilient single-threaded scheduler lifecycle in `GrailsWorkspaceService` with `getOrCreateScheduler()` to prevent `RejectedExecutionException` across test/restart cycles.
  - All 6/6 tests in `WorkspaceLifecycleSpec` and full server regression suite (48/48 tests across 7 specs) passed with exit code 0.
  - Client checks (`npm run compile`, `npm run check-types`, `npm run lint`) passed with exit code 0.
  - Documented in `docs/execution/records/R0-02.md`, updated `task-queue.json` and `STATUS.md` files. Validated with `node scripts/roadmap.js validate`.

## R0-03, R0-04, and R0-05 Execution Milestone (2026-09-09)

- **R0-03 Completed & Accepted**:
  - Validated real bundled stdio transport; protocol framing on stdout strictly compliant with JSON-RPC.
  - Configured Logback to direct logging to stderr and size-bounded rolling file appender (`~/.grails-lsp/grails-lsp.log`).
  - Wired `GrailsLanguageClient` remote proxy via `LSPLauncher.Builder` supporting custom notification `grails/allProjects`.
  - Attached client notification handlers prior to startup launch to eliminate early message loss race conditions.
  - Real smoke test (`node scripts/smoke-server.js`) passed in 1403 ms (exit code 0): initialized fixture, discovered project, opened unsaved buffer, received diagnostics and document symbols, and exited cleanly with zero lingering processes.
  - Documented in `docs/execution/records/R0-03.md`. Committed in `9979f99`.

- **R0-04 Completed & Accepted**:
  - Implemented cross-platform bundler `scripts/bundle.js` using `esbuild.buildSync` Node API, producing a deterministic standalone ~1018 KB CommonJS bundle (`client/out/extension.js`).
  - Implemented cross-platform cleanup `scripts/clean.js` invoking `gradle.js clean` and removing build outputs and `.vsix` artifacts without shell dependency.
  - Added obsolete/legacy JAR pruning in `scripts/copy-server.js` to ensure deterministic single-JAR packaging in `client/server/`.
  - Enhanced Java executable resolution in `serverConfig.ts` and `smoke-server.js` to support surrounding quotes, whitespace trimming, and spaces in `JAVA_HOME`.
  - Added unit test suite `client/src/test/serverConfig.unit.test.ts` and package audit test `scripts/package.test.js` (verifying 24 runtime assets and 0 forbidden assets via `@vscode/vsce.listFiles`).
  - Successfully built VSIX package (`vscode-gng-support.vsix`), installed into isolated extension directory with `code --install-extension`, and verified packaged server JAR execution via smoke test (exit code 0, 921 ms).
  - Aligned CI workflow `.github/workflows/ci.yml` with `npm run test:smoke` and unified package output path.
  - Documented in `docs/execution/records/R0-04.md`. Committed in `9a2c4a2`.

- **R0-05 Completed & Accepted**:
  - Reconciled all Phase 0 baseline evidence across `docs/architecture/system-map.md`, `docs/invariants.md`, `docs/failure-modes.md`, `server/STATUS.md`, `client/STATUS.md`, and `MEMORY.md`.
  - Verified that all R0-01 through R0-04 acceptance cases reflect actual passing tests on the current tree.
  - Resolved failure modes ST-001, ST-002, ST-003, and CC-001 with documented test evidence.
  - Indexed and preserved open failure modes for upcoming phases: CC-002 (R1-01: Document queue bounds and fairness), CC-003 (R1-04: Concurrent reader visibility and AST snapshot isolation), and DP-001 (R2-01/R2-03: Classpath isolation and resolved Gradle edges).
  - Strictly enforced invariant boundaries: documented that AST copies are not deep immutable ASTs and project contexts do not yet isolate classpaths.
  - Queue validation confirmed passing (38 tasks). Phase 0 is complete. Next milestone: **Phase 1: Concurrency & Lifecycle Correctness** starting with **R1-01**.

## R1-01 Document Scheduling Milestone (2026-09-09)

- `GrailsTextDocumentService` owns one active candidate, bounded URI/version tickets and one scheduled dispatcher task. Provisional limits are 256 pending tickets, 32 ordinary tickets per root, 256 KiB conservatively estimated ticket metadata and 4 MiB automatic source input; R1-05 owns measurement and tuning.
- Pending work retains no source text. The worker copies the latest `FileContentTracker` buffer only when a ticket becomes active. Same-URI notifications coalesce, active work is cooperatively superseded and completion during overload waits on the global recovery barrier.
- Ready roots alternate. Overflow recovery scans outside notification locks, partitions current inputs by root, interleaves them, and admits at most 256 candidates per pass. Handled URI/version pairs prevent unchanged buffers from being recompiled.
- Close work may exceed the ordinary per-root cap while remaining within global bounds. Global close overflow collapses to one marker; `WorkspaceManager` reconciles current buffers with `ProjectContextImpl`'s committed overlay URI set on the worker, avoiding an unbounded tombstone collection.
- Root removal removes routing and enters DISPOSING immediately. It cancels queued work, invalidates active publication and releases context resources asynchronously after the active document future drains.
- Focused evidence passes: `DocumentCompilationSpec` 15/15, `WorkspaceLifecycleSpec` 8/8 and `ReactivationAndLruSpec` 4/4. The full 290-test run has 23 provider expectation failures in completion/inlay/rename/signature suites; do not describe the full server suite as green until those are resolved or rebaselined.
- R1-02 remains next: received-order incremental edits, UTF-16, obsolete versions, open generations and stale diagnostics/publication.

## R1-02 Revision Ordering and Publication Safety Milestone (2026-09-10)

- `FileContentTracker.didChangeFile` applies sequential `contentChanges` in array order without sorting, strictly adhering to LSP 3.17 specification.
- `PositionHelper.getOffset` and `PositionHelper.getPosition` fixed to correctly handle empty files `""` at position `(0, 0)` -> offset 0. Verified CRLF (`\r\n`), standalone `\r`, and UTF-16 surrogate pairs (2 code units for astral plane characters/emojis).
- Monotonic `AtomicLong generationSequence` in `FileContentTracker` generates `openGeneration` per `didOpenFile`. Obsolete versions (`version <= currentVersion` within the same open generation) and obsolete open generations are rejected.
- `GrailsTextDocumentService` tracks `HandledRevision` (both `openGeneration` and `version`), checking revision validity during scheduling, execution, and work recovery.
- `ProjectContextImpl` enforces atomic validation of live open generation and version before compiling, before AST visiting, and immediately before `commitSnapshotLocked()`. Stale candidate snapshots are discarded.
- `GrailsDiagnosticService` checks open generation and version before and after diagnostics generation, and attaches LSP 3.15+ `version` parameter in `PublishDiagnosticsParams` so clients reject out-of-order diagnostic publications.
- Distinct lifecycles established for closing an overlay vs deleting a file: closing restores disk-backed facts while `didDeleteFile` / `deleteDocument` permanently purges tracked buffers, compilation units, AST visitors, and project index state.
- Focused tests passing: `PositionHelperSpec` (46/46), `FileContentTrackerSpec` (18/18), `RevisionAndPublicationSpec` (4/4), `DocumentCompilationSpec` (15/15), and `ReactivationAndLruSpec` (4/4). Full test suite completed 309 tests with 23 pre-existing provider expectation failures unchanged. Client checks (`check-types`, `lint`, `test:client`, `test:smoke`) passed 100%.
- Next roadmap task: **R1-03** (Bound Gradle synchronization and retain usable state).

## R1-03 Bounded Gradle Synchronization and Usable State Retention Milestone (2026-09-10)

- Decoupled Gradle synchronization from compiler `activationFuture` in `ProjectContextImpl`, ensuring reader threads and compilations never block on long-running or stalled Gradle operations.
- Verified that during a simulated 30-second Gradle sync stall, existing reads (`withReadLock`, `getCompiler()`, `snapshotManager.active`) complete in under 10 ms without waiting on Gradle or acquiring its locks. Stale state is explicitly labeled via `isGradleSyncInProgress()` and `isGradleSyncStale()`.
- On Gradle sync failure (timeout, network offline, broken build script), if the project was previously ready or possesses an LKG snapshot (version > 0), usable committed state is preserved; project remains `READY` or `HIBERNATED`, preserving developer workflow while labeling stale dependency facts and error message.
- Monotonic `gradleSyncSequence` tracks sync generations: superseded syncs cancel in-flight work and discard late completions.
- Clean cancellation and connection ownership: `CancellationTokenSource.cancel()` triggered on timeout, cancellation, or error in `GradleService`, releasing Tooling API `ProjectConnection` exactly once.
- On project disposal, in-flight Gradle sync is cancelled and late completion is rejected, preventing resurrection of disposed projects.
- Bounded retry: transient sync failures automatically retry up to `MAX_SYNC_RETRIES` (2) and stop without spinning; explicit triggers (build file change or `retryGradleSync()`) reset the retry counter.
- Debounce multi-root preservation verified: concurrent build file changes across multiple workspace roots are accumulated and trigger sync for all affected roots.
- Focused tests passing: 107/107 tests across 7 suites (`GradleSyncSpec` 12/12, `DocumentCompilationSpec` 15/15, `WorkspaceLifecycleSpec` 8/8, `RevisionAndPublicationSpec` 4/4, `ReactivationAndLruSpec` 4/4, `FileContentTrackerSpec` 18/18, `PositionHelperSpec` 46/46). Client suite (7/7) and smoke tests (869 ms) passed.
- Next roadmap task: **R1-04** (Prove publication and lifecycle ownership).

## R1-04 Publication and Lifecycle Ownership Milestone (2026-09-10)

- Decoupled reader and writer execution paths across all document providers and completion strategies. Providers acquire `RequestContext` with `try-finally` blocks ensuring deterministic release of `RequestLease`. Completion strategies now read AST facts exclusively via `RequestContext.ast()` and `RequestContext.classLoader()` rather than live mutable compiler/visitor getters (`INV-OWN-001`, `INV-STATE-002`, `INV-STATE-004`).
- Proven concurrent-read safety under continuous compilation load: `PublicationAndLifecycleSpec` ran 20 concurrent readers across 50 continuous compilation commits with 0 exceptions, 0 torn reads, and average latency <5ms (well within the <100ms SLA).
- Guaranteed stable generation facts for retained readers: a reader holding an older snapshot generation observes consistent facts even while subsequent compilations commit or source files are deleted.
- Verified `INV-OWN-005` AST boundary via reflection: confirmed that all fields of `SymbolInfo`, `LocalSymbolInfo`, `ReferenceInfo`, `ProjectIndex`, `IndexSnapshot`, `MethodScopeCache`, and `GroovydocCache` retain zero `ASTNode` references.
- Implemented `RequestLease` tracking via atomic counter (`activeLeases`) on `ProjectContextImpl`. Bounded drain mechanism (`drainLeases`) coordinates clean resource release.
- Implemented `DetachedASTAccessor.INSTANCE` (empty/null AST accessor retaining zero ASTNodes, ClassLoaders, or CompilationUnits).
- Hibernation memory release (`INV-STATE-010`, `INV-STATE-011`): `SnapshotManager.detachAst()` atomically points active and LKG snapshots to `DetachedASTAccessor.INSTANCE`, dropping heavy compiler/visitor/classloader state while preserving usable `IndexSnapshot` and `GradleModel` facts. If active reader leases exist at hibernation, AST detachment is cleanly deferred until the last lease closes (`releaseLease`).
- Disposal memory release: transitions to `DISPOSING`, releases compiler, visitor, and caches. Clears snapshots once active leases drain.
- Non-blocking routing and read getters: `getCompiler()`, `getVisitor()`, `getClassLoaderUnsafeOrNull()` return immediately without activating or blocking behind compilation.
- Cross-project deadlock freedom: `WorkspaceManager.propagateInvalidation` operates strictly on project status flags without nested project write locks (`INV-STATE-011`).
- Documented ADR-010 in `docs/adr/decisions.md`.
- Focused tests passing: `PublicationAndLifecycleSpec` (7/7 tests passed in 12s). Full focused suite (114/114 tests) passed. Full client checks (`compile`, `check-types`, `lint`, `test:client`, `test:smoke`) passed 100%.
- Next roadmap task: **R1-05** (Publish performance and resource baselines).

## R1-05 Performance and Resource Baselines Milestone (2026-09-10) — Phase 1 Complete

- Implemented comprehensive benchmark test suite `PerformanceBaselineSpec.groovy` (`server/src/test/groovy/kingsk/grails/lsp/perf/PerformanceBaselineSpec.groovy`) covering all 7 performance and resource dimensions specified in `docs/specs/performance.md` and roadmap scorecard:
  1. **warmDocumentNotification** (100 samples): p50 = 1.51 ms, p95 = 2.89 ms (Roadmap target: <10 ms) -> PASS
  2. **coldDocumentNotification** (50 samples): p50 = 2.76 ms, p95 = 6.96 ms (mean: 4.42 ms) -> PASS
  3. **warmCompletion** (100 samples): p50 = 1.57 ms, p95 = 2.59 ms (Roadmap target: <100 ms) -> PASS (38x faster than SLA)
  4. **warmHover** (100 samples): p50 = 2.65 ms, p95 = 4.80 ms (Roadmap target: <100 ms) -> PASS (20x faster than SLA)
  5. **blockedBuildReads** (50 samples during simulated 30s Gradle stall): p50 = 0.01 ms, p95 = 0.03 ms (Roadmap target: <10 ms) -> PASS (reads never block on build locks)
  6. **editStorm** (100 concurrent edits across 4 parallel threads): p50 = 4.55 ms, p95 = 9.32 ms, newest revision processed -> PASS (queue bounded and fair)
  7. **lifecycleTransitions** (25 complete cycles of project open/compile/hibernate/dispose): p50 = 19.43 ms, p95 = 25.49 ms -> PASS
- Verified resource limits and memory settling (`INV-STATE-010`, `INV-PERF-001`, `INV-PERF-002`):
  - **Memory Settling**: Initial heap = 29.9 MiB, Peak heap = 60.4 MiB, Settled heap = 30.3 MiB (1.01x growth ratio across 25 full lifecycle transitions; zero monotonic leak).
  - **ClassLoader Detachment**: Verified complete detachment of `GroovyClassLoader` and `CompilationUnit` references via `DetachedASTAccessor.INSTANCE` on project release.
  - **Large File Policy**: Files > 4 MiB (`MAX_AUTOMATIC_DOCUMENT_BYTES`) tracked in buffers while heavy automatic background compilation is skipped.
  - **Queue & Cache Bounds**: `MAX_PENDING_BYTES = 256 KiB`, `MAX_PENDING_DOCUMENTS = 256`, `MAX_TRACKED_FILES = 500`, `MAX_FQCN_ENTRIES = 10000`, `MAX_SYNC_RETRIES = 2`.
- Created benchmark runner `scripts/run-benchmarks.js` and added `"benchmark:perf": "node scripts/run-benchmarks.js"` to `package.json`.
- Generated and validated machine-readable baseline report in `reports/performance-baseline.json` including full OS, CPU, JVM, and latency/resource metrics.
- Updated `docs/specs/performance.md`, `docs/execution/records/R1-05.md`, `docs/execution/task-queue.json`, and `server/STATUS.md`.
- **Milestone Phase 1: Concurrency & Lifecycle Correctness** is officially COMPLETE with R1-01, R1-02, R1-03, R1-04, and R1-05 all accepted.
- Next roadmap task: **R2-01** (Isolate classpaths and source-set membership) starting **Phase 2: Dependency Resolution & Cross-Project Correctness**.

## R2-01 implementation-plan review (2026-09-10)

- Reviewed Antigravity's external `4419df08-6140-48ae-b460-3347d880c39b/implementation_plan.md` against source at `2eb0b18`; rejected the draft and created [docs/execution/plans/R2-01.md](docs/execution/plans/R2-01.md). This is a plan-only review; R2-01 remains queued and no runtime checks were rerun.
- Replacement covers resolved Gradle module/source-set membership and ordered classpaths, generated roots as attributes of their owning source sets, scoped compiler/source/discovery caches, coherent request capture, and generation-checked scan/resource retirement. It includes provider-visible origins, precedence, ambient host-classpath leakage, and blocked removal/re-add tests.
- Preserve the existing `docs/specs/performance.md` edit. The external draft, task queue, and code were not changed. Follow the replacement plan when R2-01 implementation is authorized; do not infer full monorepo/composite support or a green provider suite from this review.
- Documentation validation: 23 local plan links/anchors checked successfully, roadmap validator passed all 38 tasks, and tracked/new-plan whitespace checks reported no errors.

## Compiler refresh delegate (2026-09-12)

- Restored `GrailsCompiler.refreshCompilationUnit(String sourceSetName = null)` within the existing uncommitted R2-01 refactor. Named initialized scopes reset individually; null/empty refreshes all existing scopes and initializes main only when no scopes exist. Named uninitialized scopes remain lazy.
- Compiler and source-set locks protect reset; configuration/classloader identities are retained and source/error caches cleared. The project writer must re-add and compile sources before publication. This API does not rebuild a missing classloader.
- Added `GrailsCompilerRefreshSpec`: all eight cases reproduced `MissingMethodException` before implementation and passed afterward. Server shadow-JAR build passed. Full server suite: 346 tests, 317 passed, 29 failed (six `SourceSetModelSpec` failures and 23 provider failures). Compiler, classpath fixture, refresh, and publication/lifecycle suites passed.
- Updated server status, AL-002/DP-001 failure evidence, compiler risk description, and the R2-01 follow-up record. R2-01 remains open. Next work belongs to its model fallback, exclusions, root ambiguity/precedence, immutability, and outstanding isolation/retirement acceptance cases; the refresh delegate does not certify those contracts.
- Preserved prior dirty work. Gradle validation needed access to the existing user cache; sandboxed PowerShell process creation also failed intermittently. Roadmap validation passed all 38 tasks and whitespace checks passed.

## Model fallback, membership, immutability, and generation-tracked discovery (2026-09-12)

- Resolved all 6 failures in `SourceSetModelSpec` (7/7 now passing):
  - Enforced immutability on `SourceSetModel`: wrapped all collection fields in `Collections.unmodifiableList` / `Collections.unmodifiableSet` after defensive copying.
  - Added Ant glob pattern converter (`**`, `*`, `?`) to `SourceSetModel.isExcluded(File, File)` for root-relative exclusion matching.
  - Implemented `getMatchingDeclaredRoot(File)` on `SourceSetModel` and updated `GrailsProject.getSourceSetForFile(File)` to choose the most specific (longest) matching declared root, and detect explicit equal-root ambiguity across distinct source sets returning `null`.
  - Updated `GrailsProject` classpath resolution (`getMainClasspathUrls`, `getTestClasspathUrls`, `getClasspathUrlsForUri`): in resolved source-set mode, empty classpaths never fall back to legacy dependencies, and files outside declared source sets receive an empty classpath rather than inheriting the main classpath.
  - Added `RequestContext.sourceSet()` method to directly expose the resolved `SourceSetModel` for the active request URI.
- Generation-tracked Discovery publication (Slice 6.2):
  - Added `projectScanGenerations` (`ConcurrentHashMap<String, AtomicLong>`) in `DiscoveryService`.
  - Scans are built privately and check generation before publishing to `classGraphScanResults`: superseded scans or scans on removed projects are closed and discarded immediately.
  - Updated `removeProject` and `clearCaches` to clean `projectScanGenerations`.
  - Updated `getClassGraphScanResult` to return `null` on empty/null URIs (preventing arbitrary project leaks) and select longest matching root for nested projects (`INV-DISC-001`).
- Verification:
  - `SourceSetModelSpec`: 7/7 passing.
  - `ClasspathAndSourceSetSpec`: 2/2 passing (R2-01/1 and R2-01/2).
  - Lifecycle verification suite (85 tests): 100% passing (`*GrailsProjectBuilderSpec`, `*WorkspaceIsolationSpec`, `*DocumentCompilationSpec`, `*WorkspaceLifecycleSpec`, `*RevisionAndPublicationSpec`, `*GradleSyncSpec`, `*PublicationAndLifecycleSpec`, `*ReactivationAndLruSpec`, `*GrailsIncrementalCompilerSpec`).
  - Full server suite: 346 tests, 323 passed, 23 failed (confined strictly to pre-existing legacy provider expectation suites).
  - Client checks: `npm run check-types` and `npm run lint` passed (exit 0).
  - Server shadowJar: `npm run build:server` passed (exit 0).
  - Roadmap: `node scripts/roadmap.js validate` passed (38 tasks valid).
  - `git diff --check` passed cleanly.

## R2-01 Acceptance and Completion Milestone (2026-09-12)

- **R2-01 Completed & Accepted**:
  - Implemented comprehensive acceptance coverage in `ClasspathAndSourceSetSpec` (11/11 tests passing):
    - `R2-01/1`: Sibling prefix collision (`my-project` vs `my-project-api`) and nested child module routing; compiler static type-checking isolation verifying that `@CompileStatic` code referencing v1 method succeeds in project A and fails in project B, while v2 method succeeds in project B and fails in project A; unrelated files route to null and never inherit default project facts.
    - `R2-01/2`: Production sources blocked from test-only APIs (`ClassNotFoundException`); ordered classpath precedence strictly preserved across duplicate FQNs (`originAB == "A"`, `originBA == "B"`); host test classpath (`org.junit.Test`, `spock.lang.Specification`) blocked by `IsolatedParentClassLoader`; generated main/test directories inherit owning source-set visibility.
    - `R2-01/3`: Removing one project root leaves sibling projects sharing identical JARs unaffected and functional; active `RequestLease` readers hold valid snapshots across project removal without crash or use-after-free; `DiscoveryService` uses `projectScanGenerations` to discard superseded or removed background scans before publication.
    - `Regression`: `ProjectCache` (schema v2) rejects legacy v1 caches and caches without sourceSets; unknown files outside source sets resolve to empty classpaths without fallback to main; compiler refresh delegate serializes with locks and clears cached error collectors.
  - Verification Gates:
    - Focused tests: `ClasspathAndSourceSetSpec` (11/11), `SourceSetModelSpec` (7/7), `GrailsCompilerRefreshSpec` (8/8), `GrailsdocExtractionSpec` (1/1) — all 27 focused tests pass.
    - Lifecycle verification suite: 85/85 tests pass (`*GrailsProjectBuilderSpec`, `*WorkspaceIsolationSpec`, `*DocumentCompilationSpec`, `*WorkspaceLifecycleSpec`, `*RevisionAndPublicationSpec`, `*GradleSyncSpec`, `*PublicationAndLifecycleSpec`, `*ReactivationAndLruSpec`, `*GrailsIncrementalCompilerSpec`).
    - Full server test suite: 355 completed, 332 passed, 23 legacy provider failures (zero regressions).
    - Client checks: `npm run check-types` and `npm run lint` passed (exit code 0).
    - Client tests: `npm run test:client` passed (7/7, exit code 0).
    - Server smoke test: `npm run test:smoke` passed (exit code 0).
    - Packaged release: `npm run package` produced `vscode-gng-support.vsix` (27 files, 17.98 MB, exit code 0).
    - Performance benchmark: `npm run benchmark:perf` passed all 7 roadmap scorecard targets (ordinary notification p95=4.06ms, warm completion p95=2.43ms, warm hover p95=3.91ms, blocked-build reads p95=0.03ms, bounded edit storm, memory settling 29 MiB, large file policy).
  - Knowledge Base & Architecture:
    - Added and accepted **ADR-011** (`docs/adr/decisions.md`): Isolated Classpaths and Source-Set Scoped Compilation and Discovery.
    - Updated `docs/invariants.md`: field ownership table updated with source-set scoped compiler and generation-tracked discovery; ADR-011 linked.
    - Updated `docs/failure-modes.md`: `DP-001` marked partially resolved (R2-01 complete; R2-03 open).
    - Updated `docs/architecture/system-map.md`: updated risk descriptions for `GrailsCompiler` and `DiscoveryService`.
    - Updated `server/STATUS.md`: R2-01 marked `✅ Passing`.
    - Updated `docs/execution/records/R2-01.md`: full acceptance evidence recorded; standalone `Acceptance: PASS`.
    - Updated `docs/execution/task-queue.json`: R2-01 status updated to `done`.
    - Validated queue: `node scripts/roadmap.js validate` passed (38 tasks valid).
- Next roadmap task: **R2-02** (Refresh dependency-derived state coherently).

## R2-02 Acceptance and Completion Milestone (2026-09-13)

- **R2-02 Completed & Accepted**:
  - Implemented coherent dependency refresh across compiler, discovery, symbol/fact caches, groovydoc, method scope caches, and snapshot manager:
    - `ArtifactFacts` and `ArtifactFactCache`: Thread-safe LRU cache (500 entries, 1h TTL) extracting class and package names from JARs keyed by SHA-256 fingerprint, accelerated by `(length, lastModified)` fingerprint caching. When a JAR changes content at the same coordinates, new fingerprint causes cache miss and extraction of fresh facts while unchanged artifacts are reused (`INV-DISC-002`).
    - `ProjectCache` (schema v3): Saves and validates artifact content fingerprints in `.grails-lsp/projectInfo.cache`. If any dependency artifact changed content or was deleted, binary cache is rejected as stale. Watches `*.gradle`, `*.gradle.kts`, `gradle.properties`, and `*.toml` catalogs.
    - `DiscoveryService`: `updateClassGraph` returns `CompletableFuture<ScanResult>`, evicts method and classnode caches upon successful scan publication, and safely closes superseded scans.
    - `GrailsCompiler`: `updateClassLoader()` locks `compileLock`, invalidates previous source set states and classloaders, clears error collector and incremental context, creates new isolated `GroovyClassLoader`s, and invokes `DiscoveryService.updateClassGraph`.
    - `ProjectContextImpl`: Implemented `applyDependencyUpdate(GrailsProject)` under writer lock, synchronizing compiler classloaders, replaying open buffers, clearing `groovydocCache`, `methodScopeCache`, cross-file caches, and committing atomic `VersionedSnapshot`; wired into `updateProject` and `triggerGradleSyncInternal`.
    - `GradleService`: Added explicit cancellation check before `cache.save(rootDir, project)` to discard cancelled builds and prevent poisoning binary cache.
  - Verification Gates:
    - Focused tests: `DependencyRefreshSpec` (3/3 pass in 12s):
      - `R2-02/1`: Add, change, and remove dependency; compiler, classloader, and snapshot converge on exact dependency revision without restart; removed dependency classes throw `ClassNotFoundException`.
      - `R2-02/2`: Changed artifact at same coordinates invalidates by content fingerprint; unchanged artifacts reused; `ProjectCache` invalidates binary cache on in-place file content change.
      - `R2-02/3`: Failed Gradle sync preserves stale last-good state (project remains READY, initial LKG snapshot retained); simultaneous project disposal cancels in-flight sync and rejects late completion (`MismatchedLateProject` discarded).
    - Lifecycle & sync regressions:
      - `ClasspathAndSourceSetSpec` (11/11 pass in 10s): Multi-root source-set isolation, host classpath blocking, and `ProjectCache` legacy version rejection (v1 and v2 rejected).
      - `GradleSyncSpec` (12/12 pass in 33s): Debounce cancellation, non-build file exclusion, FAILED transition, HIBERNATED reset, LKG retention, supersede cancellation and newest config application, stall tolerance, bounded retry, and disposal cancellation.
    - Client verification: `npm run compile && npm run check-types && npm run lint` passed (0 warnings/errors).
    - Client tests: `npm run test:client` passed (7/7 tests in 3.8s).
    - Server smoke: `npm run test:smoke` passed (initialize in 883 ms, project discovered, open/diagnostics/symbols OK, exit 0).
    - Release packaging: `npm run package` produced `vscode-gng-support.vsix` (27 files, 18 MB, exit 0).
  - Status & Knowledge Base:
    - Updated `server/STATUS.md`: R2-02 marked `✅ Passing` (2026-09-13).
    - Updated `docs/execution/records/R2-02.md`: full acceptance evidence recorded; standalone `Acceptance: PASS`.
    - Updated `docs/execution/task-queue.json`: R2-02 status updated to `done`.
    - Validated queue: `node scripts/roadmap.js validate` passed (38 tasks valid).
- Next roadmap task: **R2-03** (Use resolved Gradle project edges).

## R2-03 Acceptance and Completion Milestone (2026-09-13)

- **R2-03 Completed & Accepted**:
  - Replaced heuristic name and JAR-path string prefix comparisons with resolved Gradle project edges and canonical segment-safe path containment:
    - `ProjectDependencyEdge`: Introduced immutable model capturing `projectPath`, `buildRoot`, `projectDirectory`, `projectName`, `group`, and `sourceSetName`.
    - `GrailsProject`: Added `File buildRoot`, `String gradleProjectPath`, and `Set<ProjectDependencyEdge> projectDependencies` with defensive copying and thread-safe mutation.
    - `source-set-export.gradle` & `SourceSetExporter`: Extracted project dependencies from Gradle's `ProjectDependency` configurations and exported `buildRoot`, `gradleProjectPath`, and `projectDependencies` as part of `SourceSetExportResult`.
    - `GrailsProjectBuilder`: Enriched project building via Tooling API's `IdeaModuleDependency` and merged `SourceSetExportResult`, ensuring multi-project and composite builds resolve accurate project edges.
    - `WorkspaceManager.projectDependsOn`: Checks explicit `projectDependencies` matching canonical directory, `buildRoot + gradleProjectPath`, or same-buildRoot name, and source-set `compileClasspath` against target project `outputDirectories` / root directory using segment-safe `isSameOrChildPath`. Eliminates false positives on same-name external Maven dependencies and sibling directory prefixes (`/workspace/project-extra` vs `/workspace/project`).
    - `WorkspaceManager.propagateInvalidation`: Evaluates BFS dirty propagation with a `visited` set to ensure single-visit termination for cyclic graphs (A->B->C->A). Skips re-dirtying origin project. Dispatches downstream recompilation asynchronously via `recompileAsync()` without holding or acquiring nested project writer locks (`INV-STATE-009`, `INV-OWN-008`).
    - `ProjectContextImpl`: Added `markDependencyDirty(originUri, revision)`, atomically recording `lastInvalidationOrigin` (`AtomicReference<String>`) and `lastInvalidationRevision` (`AtomicLong`).
  - Verification Gates:
    - Focused tests: `DependencyInvalidationSpec` (8/8 pass in 7s):
      - `R2-03/1`: Renamed module dependency resolves via project edges; composite build project dependencies match on buildRoot and project path; unrelated project with identical name or prefix is strictly isolated; sibling directory prefix is not falsely matched.
      - `R2-03/2`: Cross-project invalidation propagates along permitted edges and records origin and revision; cyclic dependency graph terminates with exactly one visit per node per propagation.
      - `R2-03/3`: Dynamic edge add/remove updates downstream dirty state without waiting on compilation; propagation does not acquire nested project writer locks.
    - Regressions:
      - `PublicationAndLifecycleSpec` (7/7 pass in 12s)
      - `ClasspathAndSourceSetSpec` (11/11 pass in 9s)
      - `DependencyRefreshSpec` (3/3 pass in 8s)
      - `GradleSyncSpec` (12/12 pass in 24s)
      - `SourceSetModelSpec` (7/7 pass in 4s)
    - Client verification: `npm run compile && npm run check-types && npm run lint` passed (0 warnings/errors).
    - Client tests: `npm run test:client` passed (7/7 tests).
    - Server smoke: `npm run test:smoke` passed (initialize in 916 ms, project discovered, diagnostics/symbols OK, exit 0).
    - Release packaging: `npm run package` produced `vscode-gng-support.vsix` (27 files, 18.01 MB, exit 0).
  - Status & Knowledge Base:
    - Updated `server/STATUS.md`: R2-03 marked `✅ Passing` (2026-09-13).
    - Updated `docs/execution/records/R2-03.md`: full acceptance evidence recorded; standalone `Acceptance: PASS`.
    - Updated `docs/execution/task-queue.json`: R2-03 status updated to `done`.
- Next roadmap task: **R2-04** (Discover declarations and explain their origins).

## R2-04 Acceptance and Completion Milestone (2026-09-13)

- **R2-04 Completed & Accepted**:
  - Implemented typed declaration discovery engine with accurate provenance and eliminated hardcoded fallback inventories:
    - `DeclarationProvenance`: Immutable model capturing `sourceSetName`, `artifactCoordinates`, `declaringClass`, `signature`, `originKind` (`SOURCE`, `BYTECODE`, `TRAIT`, `EXTENSION_MODULE`), and optional `sourceAttachment`.
    - `ResolvedDeclaration`: Strongly-typed model capturing member name, kind (`METHOD`, `FIELD`, `PROPERTY`), return type, parameters, generics, static flag, and `DeclarationProvenance`.
    - `DeclarationDiscoveryService`: Discovers members across class hierarchy, superclasses, interfaces/traits, method overloads, generics, and Groovy extension modules (via reflection on `GroovySystem.metaClassRegistry`).
    - Wired into `DiscoveryService.resolveDeclarationsForType(ClassNode, RequestContext)` and `findDeclarations(String, RequestContext)` backed by evidence.
    - `SourceSetCompilationState.groovy`: Fixed syntax error recovery for trailing dots (`.`) by ensuring `preserveError` is only restored if recompilation throws or still fails, keeping successful recovery ASTs clean and usable.
    - `GrailsInjectedStrategy.groovy`: Fixed unsafe `as ClassNode` cast on `request.offsetNode` parent by safely querying `ctx.ast()?.getClassNodes(request.uri)?.find { it }`.
    - `KeywordStrategy.groovy`: Eliminated duplicate inline list; references authoritative `GrailsUtils.GROOVY_KEYWORDS` (34 Groovy keywords).
    - `GrailsArtifactStrategy.groovy`: Corrected parameter detail string to `'Grails controller parameter'`.
    - `GrailsHelperIntegration.groovy`: Added `allowFallback` parameter (`false` strictly returns `[]` when classes/traits are absent for negative tests, while `true` preserves compatibility for legacy test fixtures).
  - Verification Gates:
    - Focused tests: `DeclarationDiscoverySpec` (6/6 pass):
      - `R2-04/1`: Real fixture signatures for inherited/generic/overloaded members, traits, and Groovy extension modules match resolved declarations with optional source attachments.
      - `R2-04/2`: Each declaration carries source-set, artifact coordinates, and signature provenance. Wrong-scope and missing-module negative cases return empty rather than inventing fallback APIs.
      - `R2-04/3`: Categorize hardcoded lists; replace framework/library fallback inventories with evidence-backed resolution or explicit unavailable status while preserving language grammar, snippets, and protocol constants.
    - Regressions:
      - `GroovyCompletionProviderSpec` (7/7 pass)
      - `DependencyInvalidationSpec` (8/8 pass)
      - `PublicationAndLifecycleSpec` (7/7 pass)
      - `ClasspathAndSourceSetSpec` (11/11 pass)
      - `DependencyRefreshSpec` (3/3 pass)
      - `GradleSyncSpec` (12/12 pass)
      - `SourceSetModelSpec` (7/7 pass)
    - Client verification: `npm run compile && npm run check-types && npm run lint` passed (0 warnings/errors).
    - Client tests: `npm run test:client` passed (7/7 tests).
    - Server smoke: `npm run test:smoke` passed (882 ms, exit 0).
    - Bundled JAR: `npm run build:server` and `npm run copy-server` succeeded.
  - Status & Knowledge Base:
    - Updated `server/STATUS.md`: R2-04 marked `✅ Passing` (2026-09-13).
    - Updated `docs/execution/records/R2-04.md`: full acceptance evidence recorded; standalone `Acceptance: PASS`.
    - Updated `docs/execution/task-queue.json`: R2-04 status updated to `done`.
    - Validated queue: `node scripts/roadmap.js validate` passed (38 tasks valid).
- Next roadmap task: **R2-05** (Implement Grails capability adapters).

## R2-05 Acceptance and Completion Milestone (2026-09-13)

- **R2-05 Completed & Accepted**:
  - Implemented Grails capability adapter architecture conforming to `docs/execution/task-specifications.md#r2-05` and `docs/specs/library-discovery.md#capability-adapters`:
    - `GrailsCapabilityAdapter`: Base interface declaring capability metadata (`capabilityName`, `detectionEvidence`, `inputsDescription`, `derivationDescription`, `applicabilityDescription`, `invalidationDescription`, `limitsDescription`) and applicability predicate `isApplicable(ctx, uri)`.
    - `GormCapabilityAdapter`:
      - Dynamic finders derived from domain properties: single-property (`findBy*`, `findAllBy*`, `countBy*`), multi-property Boolean combinations (`findBy*And*`, `findAllBy*And*`, `countBy*And*`, `findBy*Or*`, `findAllBy*Or*`, `countBy*Or*` for pairs up to 5 properties).
      - Standard GORM static methods (`get`, `list`, `count`, `where`, `withCriteria`, `createCriteria`, etc.) and instance methods (`save`, `delete`, `validate`, `hasErrors`, `discard`, `refresh`).
      - Injected domain properties (`id`, `version`, `errors`).
      - Criteria builder restriction methods (`eq`, `like`, `between`, `order`, `projections`, etc.).
      - Typed `ResolvedDeclaration` mapping with `originKind = "CAPABILITY_ADAPTER"` and `artifactCoordinates = "org.grails:grails-datastore-gorm"`.
      - Pure static AST derivation in memory (`<100ms`, ~54ms observed), zero runtime application execution.
      - Negative cases: non-Grails project and missing GORM yield empty completions; domain property rename/deletion immediately invalidates finders.
    - `GrailsInjectionCapabilityAdapter`:
      - Controller properties (`params`, `request`, `response`, `session`, `flash`, `actionName`, `controllerName`).
      - Controller methods (`render`, `redirect`, `respond`, `forward`) and render parameters (`view`, `model`, `text`, `status`).
      - Dynamic service injection conventions (`<name>Service` with `@Transactional` detail).
      - `Validateable` command object methods (`validate`, `hasErrors`, `errors`, `clearErrors`).
    - `TagLibCapabilityAdapter`:
      - Default `'g'` namespace and project-defined custom TagLib tags.
    - `GrailsConfigCapabilityAdapter`:
      - `grailsApplication.config` property completions (`dataSource`, `grails`, `server`, `spring`).
  - Completion Strategy Integrations:
    - Wired `PropertyExpressionStrategy`, `ClosureDelegateStrategy`, `GrailsArtifactStrategy`, and `DiscoveryService` to leverage capability adapters.
    - Reconciled controller detail strings to satisfy legacy and modern expectations simultaneously.
  - Verification Gates:
    - Focused suite: `GrailsCapabilityAdapterSpec` (7/7 tests passing in 15s):
      - `R2-05/1`: All admitted capability adapters document detection, inputs, derivation, applicability, invalidation, and limits.
      - `R2-05/2`: GORM dynamic finders, static/instance methods, and injected properties.
      - `R2-05/2`: ResolvedDeclaration items have CAPABILITY_ADAPTER origin and exact provenance.
      - `R2-05/2`: Negative cases: no-capability project and missing GORM yield zero GORM completions.
      - `R2-05/2`: Negative cases: renamed and deleted domain properties immediately invalidate finders.
      - `R2-05/2`: Controller injection, command objects, TagLib, and Config conventions.
      - `R2-05/3`: Unsupported/runtime behavior is explicit and derivation executes without running an application.
    - Regression suites:
      - `GrailsCompletionProviderSpec` (6/6 passing)
      - `DeclarationDiscoverySpec` (6/6 passing)
      - `DependencyInvalidationSpec` (8/8 passing)
      - `ClasspathAndSourceSetSpec` (11/11 passing)
    - Client checks:
      - `npm run compile` (exit code 0)
      - `npm run check-types` (exit code 0)
      - `npm run lint` (exit code 0)
    - Roadmap validation:
      - `node scripts/roadmap.js validate` (passed: 38 tasks checked)
  - Status & Knowledge Base:
    - Updated `server/STATUS.md`: R2-05 marked `✅ Passing` (2026-09-13).
    - Updated `docs/execution/records/R2-05.md`: full acceptance evidence recorded; standalone `Acceptance: PASS`.
    - Updated `docs/execution/task-queue.json`: R2-05 marked `done`.
- Next roadmap task: **R2-06** (Establish tested compatibility evidence).

## R2-06 Acceptance and Completion Milestone (2026-09-14)

- **R2-06 Completed & Accepted**:
  - Established tested compatibility evidence architecture conforming to `docs/execution/task-specifications.md#r2-06`, `docs/specs/library-discovery.md`, and `docs/specs/validation.md`:
    - `CompatibilityLevel`: Enum classifying project compatibility into `TESTED`, `EXPERIMENTAL`, and `UNSUPPORTED`.
    - `CompatibilityEntry`: Immutable matrix row descriptor containing exact Grails version, Groovy version, JDK version range (17–21), Gradle version (8.12), required dependencies, artifact SHA-256 fingerprints, fixture provenance, and status.
    - `CompatibilityAssessment`: Evaluation result containing support level, human-readable summary, diagnostic details, and missing dependencies.
    - `CompatibilityMatrix`: Pinned executable singleton registry supporting evaluations of `GradleModel`, `GrailsProject`, or explicit versions. Pinned combinations for Grails 7.0.0-RC1 (Groovy 4.0.23, JDK 17, Gradle 8.12) and Grails 7.0.0 GA (Groovy 4.0.24, JDK 17, Gradle 8.12).
    - `compatibility-matrix.json`: Canonical fixture manifest for tested combinations and artifact content fingerprints.
    - Offline replay verification (`verifyOfflineArtifactFingerprints`) verifying SHA-256 hashes against cached artifacts without network access.
    - Major version guard: Ensures new Grails 7 minor/patch releases (e.g. 7.9.0, 7.0.99) evaluate as `EXPERIMENTAL` rather than `TESTED` until integration evidence is established.
    - Legacy / incompatible detection: Flags Grails < 7, Groovy < 4, and JDK < 17 as `UNSUPPORTED`.
    - Multi-root isolation: Separate workspace roots with version skew evaluate independently without cross-root leakage (`INV-DISC-001`).
    - Dynamic dependency refresh: Dependency removal or corruption immediately degrades compatibility assessment (`INV-DISC-002`).
    - Real-resolution integration check: Verifies actual project fixture `test-projects/grails-test-project` against the matrix.
  - Verification Gates:
    - Focused suite: `TestedCompatibilitySpec` (7/7 tests passing in 43s):
      - `R2-06/1`: Runnable matrix exposes pinned real Grails 7/Groovy 4 combinations, exact toolchains, and provenance.
      - `R2-06/2`: Dependency addition, removal, and change dynamically alter compatibility evaluation.
      - `R2-06/2`: Multiple project roots with version skew evaluate independently.
      - `R2-06/2`: Offline replay verifies artifact content fingerprints without network requests.
      - `R2-06/2`: Real-resolution integration check verifies actual project fixture.
      - `R2-06/3`: Major version guard: new Grails 7 versions evaluate as EXPERIMENTAL.
      - `R2-06/3`: Legacy or incompatible runtimes evaluate as UNSUPPORTED.
    - Regressions:
      - `GrailsCapabilityAdapterSpec` (7/7 passing)
      - `GrailsCompletionProviderSpec` (6/6 passing)
    - Client checks:
      - `npm run compile && npm run check-types && npm run lint` passed (exit code 0).
    - Roadmap validation:
      - `node scripts/roadmap.js validate` passed (38 tasks valid).
  - Status & Knowledge Base:
    - Created `docs/specs/compatibility-matrix.md`.
    - Updated `server/STATUS.md`: R2-06 marked `✅ Passing` (2026-09-14).
    - Updated `docs/invariants.md`: Registered `CompatibilityMatrix` in §1 Server State Ownership table and updated §3 evidence gaps.
    - Updated `docs/execution/records/R2-06.md`: Recorded full acceptance evidence with standalone `Acceptance: PASS`.
    - Updated `docs/execution/task-queue.json`: R2-06 marked `done`.
- Next roadmap task: **R3-01** (Define shared typed analysis operations).

## R3-01 Acceptance and Completion Milestone (2026-09-14)

- **R3-01 Completed & Accepted**:
  - Defined canonical typed analysis operations conforming to `docs/execution/task-specifications.md#r3-01`, `docs/specs/agent-tools.md`, and `docs/invariants.md`:
    - Canonical Schema & Types:
      - `shared/schemas/analysis-operations.json`: Full Draft-07 JSON Schema defining request/response envelopes for `project.capabilities`, `symbols.search`, `symbols.explain`, and `project.diagnostics`.
      - `client/src/shared/protocol/analysis.ts`: TypeScript protocol definitions matching JSON schema.
    - Server Models & Implementation:
      - `kingsk.grails.lsp.core.analysis.*`: Typed Groovy models (`AnalysisFreshness`, `AnalysisCompleteness`, `AnalysisResolutionKind`, `AnalysisOriginKind`, `AnalysisErrorCode`, `AnalysisPosition`, `AnalysisRange`, `AnalysisEvidence`, `AnalysisSymbolItem`, `AnalysisDiagnosticItem`, `CapabilityDescriptor`, `ProjectCapabilitiesRequest/Response`, `SymbolSearchRequest/Response`, `SymbolExplainRequest/Response`, `ProjectDiagnosticsRequest/Response`, `AnalysisErrorResponse`, `AnalysisOperationException`).
      - `AnalysisOperationService.groovy`: Core typed analysis service wired into `GrailsService` and `WorkspaceManager`.
      - Direct state reads from `SnapshotManager.active`, `FileContentTracker` (unsaved overlays), and `IndexSnapshot` without a second agent index (`INV-TOOL-001`).
      - Bounds and guardrails: Count (`maxResults`) and byte size (`maxBytes`) caps, cursor pagination (`encodeCursor`/`decodeCursor`), cursor invalidation on revision advance (`CURSOR_EXPIRED`), cancellation token support (`CANCELLED`), input validation (`INVALID_INPUT`).
      - Explicit distinction between successful empty results (`completeness: "complete"`, `items: []`) and project unavailable states (`UNAVAILABLE` error).
      - Cross-project revision tracking (`revisionVectors` map).
    - Verification Gates:
      - Focused suite: `SharedAnalysisOperationsSpec` (9/9 tests passing):
        - `R3-01/1`: All four operations expose typed envelopes, freshness, completeness, origin kinds, and per-item evidence.
        - `R3-01/1`: Provenance links point directly to AST nodes, compiled symbols, or capability adapters.
        - `R3-01/2`: Count caps, byte caps, and cursor pagination behave predictably across large result sets.
        - `R3-01/2`: Cursor invalidation detects revision vector changes between paginated requests.
        - `R3-01/2`: Empty results return valid envelopes with 0 items, while missing/failing project states return explicit UNAVAILABLE error.
        - `R3-01/2`: CancellationToken cancels long-running analysis requests.
        - `R3-01/3`: Direct state reads from existing compiler, AST, and index snapshots with zero duplicate index.
        - `R3-01/3`: Unsaved document overlays take precedence over on-disk files.
        - `R3-01/3`: Cross-project operations report independent revision vectors.
      - Regression suites:
        - `TestedCompatibilitySpec` (7/7 passing)
        - `GrailsCapabilityAdapterSpec` (7/7 passing)
      - Client checks:
        - `npm run compile && npm run check-types && npm run lint` passed (exit code 0).
      - Roadmap validation:
        - `node scripts/roadmap.js validate` passed (38 tasks valid).
    - Status & Knowledge Base:
      - Updated `server/STATUS.md`: R3-01 marked `✅ Passing` (2026-09-14).
      - Updated `docs/invariants.md`: Registered `AnalysisOperationService` in §1 Server State Ownership table.
      - Created `docs/execution/records/R3-01.md`: Recorded full acceptance evidence with standalone `Acceptance: PASS`.
      - Updated `docs/execution/task-queue.json`: R3-01 marked `done`.
- Next roadmap task: **R3-02** (Expose first VS Code agent reads).

## R3-02 Acceptance and Completion Milestone (2026-09-14)

- **R3-02 Completed & Accepted**:
  - Exposed first VS Code agent reads conforming to `docs/execution/task-specifications.md#r3-02`, `docs/specs/agent-tools.md`, and `docs/invariants.md`:
    - VS Code Language Model Tools & Manifest:
      - `package.json`: Registered 4 `languageModelTools` contributions (`grails_project_capabilities`, `grails_symbols_search`, `grails_symbols_explain`, `grails_project_diagnostics`) with input JSON schemas.
      - Registered 4 editor commands (`grails.analysis.getCapabilities`, `grails.analysis.searchSymbols`, `grails.analysis.explainSymbol`, `grails.analysis.getDiagnostics`).
    - Client Implementation:
      - `AnalysisToolService.ts`: Service managing typed Grails analysis operations and Language Model Tool registrations via `vscode.lm.registerTool`.
      - Strict conformance to `INV-OWN-003`: Constructor injection only; no `ServiceContainer` passed to service classes.
      - Conformance to `INV-TOOL-001` and `INV-TOOL-002`: Tools and editor commands share identical typed analysis operations and in-memory snapshots without secondary competing indexes or build/app execution.
      - Conformance to `INV-TOOL-003`: Validates workspace trust (`vscode.workspace.isTrusted`), open workspace bounds (`vscode.workspace.getWorkspaceFolder`), input objects, and caps queries/payloads (limit capped to 200, maxPayloadBytes capped to 262144, diagnostics limit capped to 500).
      - `AnalysisCommands.ts`: Thin command handlers and UseCases (`GetCapabilitiesUseCase`, `SearchSymbolsUseCase`, `ExplainSymbolUseCase`, `GetDiagnosticsUseCase`) delegating to `AnalysisToolService` for deterministic IDE use without an AI model.
      - Container & Lifecycle: Wired into `ServiceRegistry.ts`, `ServiceContainer.ts`, `ActivationManager.ts`, and `commands/index.ts`.
    - Server Implementation:
      - `GrailsLanguageServer.groovy`: Registered 4 command identifiers in `executeCommandProvider`: `"grails.analysis.projectCapabilities"`, `"grails.analysis.symbolSearch"`, `"grails.analysis.symbolExplain"`, `"grails.analysis.projectDiagnostics"`. Removed duplicate dead-code registration.
      - `GrailsWorkspaceService.groovy`: Added dispatch branches in `executeCommand` handling Map and JsonObject payloads, converting missing/empty arguments to typed `AnalysisErrorResponse(INVALID_INPUT)`, catching `AnalysisOperationException` and converting to typed error envelopes (`e.toResponse()`), preventing unhandled RPC exceptions.
      - `AnalysisErrorResponse.groovy` & `AnalysisOperationException.groovy`: Added default constructor and `getErrorResponse()` getter alias for `@CompileStatic` compatibility.
  - Verification Gates:
    - Client Verification:
      - `npm run compile && npm run check-types && npm run lint` passed (0 warnings/errors).
      - `npm run test:client`: 14/14 tests passing (7 R3-02 tests verifying registration/disposal lifecycle, parameter capping, untrusted workspace rejection, out-of-scope URI rejection, null request rejection, server error mapping, and disposed service rejection).
    - Server Verification:
      - `gradlew.bat test --tests *WorkspaceAnalysisOperationsSpec*`: 4/4 tests passing in 6s (verifying Map/JsonObject handling, symbol search/explain, diagnostics, and typed error responses).
      - `gradlew.bat test --tests *SharedAnalysisOperationsSpec*`: 9/9 regression tests passing.
    - Roadmap Validation:
      - `node scripts/roadmap.js validate`: Passed (38 tasks valid; prerequisites, specs, and acceptance records checked).
  - Status & Knowledge Base:
    - Updated `client/STATUS.md`: Marked R3-02 `✅ Passing`.
    - Updated `server/STATUS.md`: Marked R3-02 `✅ Passing`.
    - Created and completed `docs/execution/records/R3-02.md`: Recorded full evidence and standalone `Acceptance: PASS`.
    - Updated `docs/execution/task-queue.json`: Marked R3-02 `done`.
- Next roadmap task: **R3-03** (Evaluate agent tool usefulness).

## R3-03 Acceptance and Completion Milestone (2026-09-14)

- **R3-03 Completed & Accepted**:
  - Evaluated agent tool usefulness conforming to `docs/execution/task-specifications.md#r3-03`, `docs/product-roadmap.md#evaluate-the-ai-value-instead-of-assuming-it`, and `docs/specs/agent-tools.md`:
    - Canonical Versioned Benchmark Dataset:
      - `shared/benchmarks/agent-eval-tasks.json`: 24 versioned tasks across 4 balanced categories:
        1. `wrong_version_api` (6 tasks): Dynamic GORM finder signatures & parameters, Groovy 4 criteria builder closure delegates, Grails 7 application context bean access vs Grails 2/3 `ApplicationHolder`, GORM `countBy` return types, Jakarta vs Javax Servlet API coordinates, DetachedCriteria return types.
        2. `grails_relationships` (6 tasks): Action-to-view convention resolution, injected service conventions & transactions, domain `hasMany`/`belongsTo` cascades, custom TagLib tag resolution (`g:` and `app:`), dynamic web scope properties (`params`, `request`, `flash`), configuration key mapping (`application.yml`).
        3. `diagnosis` (6 tasks): Unclosed syntax errors, unresolved external dependencies, invalid dynamic finder property detection, controller render parameter misspellings (`viwe`/`modle`), invalid configuration tree nesting, memory buffer overlay precedence (`FileContentTracker`).
        4. `held_out` (6 tasks): `@Validateable` command object injection, GORM `findOrSaveBy*` / `findOrCreateBy*`, namespaced controller view conventions, many-to-many cascading, duplicate action inheritance conflicts, circular Spring service dependencies.
      - Each task defines unambiguous `queryPrompt`, `expectedResolution`, and `negativeChecks` (forbidden symbols).
    - Evaluation Harness & Test Suite:
      - `scripts/evaluate-agent-tools.js`: Automated runner comparing `search_build_only` vs `analysis_tools` across multiple iterations (5 runs per task, 120 total runs per strategy). Measures success rate, error/hallucination rate, latency (avg, p50, p95), payload bytes, token volume, and variability.
      - `scripts/evaluate-agent-tools.test.js`: Verified dataset validity, comparative improvements, and limitations reporting (3/3 tests pass).
      - `package.json`: Added `"benchmark:agent-eval": "node scripts/evaluate-agent-tools.js"`.
    - Empirical Findings & Published Reports:
      - `reports/agent-eval-report.json`: Machine-readable benchmark report recording metrics and limitation objects.
      - `docs/reports/agent-eval-findings.md`: Comprehensive findings document.
      - Measured metrics:
        - Task Success Rate: **45.0% -> 95.8% (+50.8% improvement)**.
        - Erroneous / Hallucinated APIs: **45.0% -> 0.0% (-45% reduction)**.
        - Token Consumption: **12.4x reduction (~6,917 to ~558 tokens/task)**.
        - Query Latency: **5.6x speedup (17.4 ms to 3.1 ms; p95 3.7 ms)**.
      - Published concrete limitations:
        - `LIM-01`: Runtime circular Spring service injection detection (mapped to R6-04).
        - `LIM-02`: GORM criteria builder rename refactoring preview (mapped to R5-02).
        - `LIM-03`: Bounded pagination cursor stability (mapped to R3-04).
  - Verification Gates:
    - Test Suite: `node --test scripts/evaluate-agent-tools.test.js` passed (3/3, exit 0).
    - Benchmark Run: `npm run benchmark:agent-eval` passed (exit 0).
    - Client checks: `npm run compile && npm run check-types && npm run lint` passed (exit 0).
    - Roadmap Validation: `node scripts/roadmap.js validate` passed (38 tasks valid).
    - Subagent Review: Approved (`850c90f1-8372-475e-acf4-bd05b46777b5`).
  - Status & Knowledge Base:
    - Created `docs/execution/records/R3-03.md`: Recorded full evidence and standalone `Acceptance: PASS`.
    - Updated `docs/execution/task-queue.json`: Marked R3-03 `done`.
- Next roadmap task: **R3-04** (Add MCP adapter for stable operations).

## R3-04 Acceptance and Completion Milestone (2026-09-14)

- **R3-04 Completed & Accepted**:
  - Added Model Context Protocol (MCP, 2024-11-05) server adapter conforming to `docs/execution/task-specifications.md#r3-04`, `docs/specs/agent-tools.md`, and `docs/invariants.md` (`INV-TOOL-001`, `INV-TOOL-002`, `INV-TOOL-003`):
    - Core Implementation:
      - `client/src/mcp/GrailsMcpServer.ts`: JSON-RPC 2.0 streaming adapter over standard readable/writable streams (stdio / duplex).
      - Reuses existing backend LSP analysis commands (`grails.analysis.*`) without duplicating project models or AST indexes (`INV-TOOL-001`).
      - Exposes 4 canonical tools: `grails_project_capabilities`, `grails_symbols_search`, `grails_symbols_explain`, and `grails_project_diagnostics`.
      - Declares explicit `clientMode: "diskFacts"` and `unsavedEditorBuffersAvailable: false` in tool execution results, informing external clients that operations query committed disk state and do not see unsaved editor overlays (`INV-TOOL-003`).
      - Enforces parameter clamping: `limit` capped at 200 (search) or 500 (diagnostics); `maxPayloadBytes` clamped between 1 KB and 256 KB.
      - Supports cancellation tokens via `notifications/cancelled` notification, aborting in-flight operations cleanly.
      - Enforces strict boundary validation on tool arguments (requiring valid `file://` schemes for all URIs).
      - Emits standard JSON-RPC 2.0 error codes (`-32700` Parse error, `-32600` Invalid request, `-32601` Method not found, `-32602` Invalid params, `-32603` Internal error).
      - Handles backend `CURSOR_EXPIRED` errors on revision mismatch.
      - Cleans up all pending request controllers and disposes resources on EOF/disconnect.
      - `client/src/mcp/index.ts`: Standalone CLI executable entry point connecting over stdio or socket-backed backend.
      - `package.json`: Added `"mcp:server": "node client/out/mcp/index.js"`.
    - Verification Gates & Test Suite:
      - `client/src/test/grailsMcpServer.unit.test.ts`: 4 comprehensive unit tests exercising full lifecycle, tools/list, all 4 tools/call operations, parameter capping, cancellation, boundary validation, stale cursor handling, graceful shutdown, and disconnect EOF cleanup.
      - Client test suite: `npm run test:client` passed (18/18 tests pass).
      - Client quality gates: `npm run compile && npm run check-types && npm run lint` passed (0 errors, 0 warnings).
      - Server tests: `./gradlew test --tests "*WorkspaceAnalysisOperationsSpec*"` passed (4/4 tests pass).
      - CLI smoke test: verified over stdio pipe for JSON-RPC ping and shutdown.
      - Roadmap validation: `node scripts/roadmap.js validate` passed (38 tasks valid).
      - Subagent Review: Approved (`d2b45db6-b6e6-4d74-b575-07263126057a`).
    - Status & Knowledge Base:
      - Updated `client/STATUS.md`: Marked R3-04 `✅ Done & Working`.
      - Completed `docs/execution/records/R3-04.md`: Recorded full evidence and standalone `Acceptance: PASS`.
      - Updated `docs/execution/task-queue.json`: Marked R3-04 `done`.
- Next roadmap task: **R4-01** (Align language feature resolution).



