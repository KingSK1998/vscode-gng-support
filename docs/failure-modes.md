# Failure Mode Registry

> **Status:** ACTIVE — add entries within 30 minutes of discovering a bug.
> **Owner:** @kingsk (sole maintainer)
> **Last updated:** 2026-09-13
> **Validation triggers:** Bug fixed, architecture change
> **Rule:** Every agent surprise → one entry here. No exceptions.

---

## Index

### By Component

| Component | Failure Modes |
|---|---|
| GrailsService | CI-001 |
| ASTService | SM-001 |
| ThreadSafeLruCache | RL-001 |
| ProviderRegistry / BaseProvider | PC-001 |
| GrailsIncrementalCompilerSpec | AL-001 |
| GrailsCompiler / SourceSetCompilationState | AL-002, AL-003 |
| Client startup / packaging | ST-001 |
| Stdio transport / Logback | ST-002 |
| Server workspace / Source duplication | ST-003 |
| Background document compilation / ProjectContextImpl | CC-001, CC-002, CC-003 |
| Classpath isolation / Multi-project | DP-001 |

### By Invariant

| Invariant | Failure Modes |
|---|---|
| `INV-OWN-001` | PC-001 |
| `INV-OWN-004` | SM-001, CI-001, RL-001, AL-002 |
| `INV-OWN-005` | CC-003 |
| `INV-OWN-008` | DP-001 |
| `INV-STATE-001` | CC-003 |
| `INV-STATE-002` | PC-001, ST-003 |
| `INV-STATE-003` | CI-001 |
| `INV-STATE-004` | CC-001, CC-003, AL-003 |
| `INV-STATE-005` | CC-002 |
| `INV-PERF-001` | CC-002 |
| `INV-PERF-002` | CC-002 |
| `INV-DISC-001` | DP-001 |

---

## How To Add An Entry

```markdown
### XX-NNN: Short Title
- **Status:** Open | Active | Resolved | Historical
- **Bug:** What happened
- **Invariant violated:** INV-XXX-NNN
- **Root cause:** Why
- **Fix:** What was done
- **Test:** Test name or "NONE — ADD ONE"
- **Detected by:** How found
- **Related ADRs:** ADR-NNN
- **Date:** YYYY-MM-DD
```

### Entry Lifecycle

| Status | Meaning | Transition when |
|---|---|---|
| **Open** | Observed unresolved bug; fix or required validation remains incomplete | Record owning roadmap task and current evidence |
| **Active** | Fixed but pattern could recur | Default for new entries |
| **Resolved** | Root cause eliminated by architecture change | ADR or structural change prevents recurrence |
| **Historical** | Reference only. Skip during review | 6+ months, no recurrence, architecture moved past |

---

## Categories

| Category | Covers |
|---|---|
| State Mutation | Unauthorized writes to shared state |
| Cache Invalidation | Stale data served after state change |
| Concurrency | Race conditions, deadlocks, partial visibility |
| AST Lifecycle | Compiler/visitor ordering, null AST references |
| Provider Contract | Tier violations, missing cancellation/health |
| Resource Leak | Memory not freed, executors not shut down |

---

## State Mutation

### SM-001: AST Service Memory Leak — Artifact Map Duplication
- **Status:** Resolved
- **Bug:** `ASTService` cached `ClassNode` refs by URI. On recompilation, old nodes never evicted → memory leak + query pollution.
- **Invariant violated:** `INV-OWN-004`
- **Root cause:** No eviction in visitor pipeline. `visitSourceUnit` appended without clearing.
- **Fix:** Added `clearUri(String uri)` in `ASTService`. Eviction wired into `GrailsASTVisitor.visitSourceUnit`.
- **Test:** `GrailsIncrementalCompilerSpec` — "ASTService artifact cache rebuilding"
- **Detected by:** Architecture review (Phase 1)
- **Related ADRs:** ADR-006
- **Date:** 2026-06-03

---

## Cache Invalidation

### CI-001: Incomplete Cross-File AST Cache Invalidation
- **Status:** Resolved
- **Bug:** Local AST evicted per-file, but cross-file caches (`DiscoveryService`, completion) had no invalidation trigger. Stale data after cross-file changes.
- **Invariant violated:** `INV-STATE-003`
- **Root cause:** Invalidation was file-scoped only. No cross-file trigger on incremental compile.
- **Fix:** Added `clearCrossFileCaches()` in `GrailsService`, `clearSymbolCaches()` in `DiscoveryService`. Wired at END of incremental cycle.
- **Test:** `GrailsIncrementalCompilerSpec` — "cross-file cache eviction"
- **Detected by:** Architecture review (Phase 1)
- **Related ADRs:** ADR-004
- **Date:** 2026-06-03

---

## Resource Leak

### RL-001: ThreadSafeLruCache Static Executor Lifecycle
- **Status:** Resolved
- **Bug:** Static scheduled executor for cache expiration. `shutdown()` killed it globally — executor rejection on restart.
- **Invariant violated:** `INV-OWN-004`
- **Root cause:** Static final executor, no re-initialization path.
- **Fix:** Non-final static ref with lazy `getExecutor()` re-init. `shutdown()` terminates + nulls. Next access recreates.
- **Test:** `ThreadSafeLruCacheSpec` — multiple restart cycles
- **Detected by:** Test failure on server restart
- **Related ADRs:** ADR-002
- **Date:** 2026-06-03

---

## AST Lifecycle

### AL-003: Premature Error Re-attachment During Dot Recovery
- **Status:** Resolved
- **Bug:** Trailing-dot syntax error recovery recompilations re-attached previous syntax errors from `preserveError` back into `errorCollector` even when recovery recompilation succeeded, leaving `hasErrors()` true and discarding the generated AST.
- **Invariant violated:** `INV-STATE-004`
- **Root cause:** In `SourceSetCompilationState.compile()`, `preserveError` was unconditionally restored after recompilation instead of only restoring when recompilation failed.
- **Fix:** In `SourceSetCompilationState.compile()`, only restore `preserveError` into `compilationUnit.errorCollector` if the second compilation attempt throws an exception or still produces errors.
- **Test:** `GroovyCompletionProviderSpec`
- **Detected by:** Provider regression debugging (R2-04)
- **Date:** 2026-09-13.

### AL-002: Compiler Refresh Delegate Lost During Source-Set Refactor
- **Status:** Active
- **Bug:** Calls to `GrailsCompiler.refreshCompilationUnit()` fail after the reset implementation moves into `SourceSetCompilationState`.
- **Invariant affected:** `INV-OWN-004` requires an owned invalidation path for derived compiler state.
- **Root cause:** The source-set refactor retained compilation-unit getter/setter delegates but omitted the refresh delegate.
- **Fix:** Restore the compiler delegate for a named existing scope or all initialized scopes, with lazy main initialization when refreshing an empty compiler. Serialize resets with compiler/state locks and clear source/error caches while retaining configuration/classloader identities. The project writer must re-add sources and compile before publication; this operation does not rebuild missing classloaders.
- **Test:** `GrailsCompilerRefreshSpec`: eight cases failed with `MissingMethodException` before the fix and passed afterward, including in the full server suite. Server build passed; full suite reports 29 failures in model/provider suites.
- **Detected by:** Refresh API review and focused regression tests.
- **Date:** 2026-09-12.

### AL-001: Stub Test — Zero Regression Protection
- **Status:** Resolved
- **Bug:** `GrailsIncrementalCompilerSpec` contained only `expect: true`. Zero protection for most critical pipeline.
- **Invariant violated:** Testing mandate (CODING_STANDARDS §Testing)
- **Root cause:** Placeholder never filled in.
- **Fix:** Replaced with 7 integration tests covering AST update, cache eviction, node removal, diagnostics, syntax error isolation, position lookup.
- **Test:** `GrailsIncrementalCompilerSpec` (7 cases)
- **Detected by:** Architecture review
- **Related ADRs:** —
- **Date:** 2026-06-03

---

## Provider Contract

### PC-001: Old Constructor Pattern Leaking GrailsService
- **Status:** Resolved
- **Bug:** Providers used `private final GrailsService service` directly. Had access to write methods they must never call.
- **Invariant violated:** `INV-STATE-002`, `INV-OWN-001`
- **Root cause:** Pre-architecture pattern carried forward. No enforcement.
- **Fix:** All T1 providers migrated to `BaseProvider(ProviderContext, CompilationContext, ProjectContext)`. Old field removed.
- **Test:** `@CompileStatic` catches direct state access at compile time
- **Detected by:** Architecture review
- **Related ADRs:** ADR-005
- **Date:** 2026-06-07

---

## Startup and Interrupted Concurrency Work

### ST-001: Installed Extension Uses Development Startup Defaults
- **Status:** Resolved (verified in R0-04)
- **Bug:** The manifest default selected a manual TCP server; Java configuration was ignored by the launcher, application/LSP port definitions collided, and initial project notifications could arrive before handlers were registered.
- **Invariant violated:** Client lifecycle/configuration ownership; `INV-STATE-006` fail-safe defaults.
- **Root cause:** Development defaults and post-start notification registration persisted into the production path.
- **Fix:** Source changes select the bundled JAR by default, honor the configured Java installation (with quote stripping and spaces support), separate ports and register project handlers before startup. Deterministic artifact copying and VSIX packaging verified.
- **Test:** Headless tests pass via `npm run test:client`; local installation into isolated extensions dir verified with `code --extensions-dir <tempDir> --install-extension vscode-gng-support.vsix` and passing smoke workflow.
- **Detected by:** September startup audit and failing regression tests.
- **Date:** 2026-09-09.

### ST-002: Logs Corrupt Stdio Protocol Framing
- **Status:** Resolved (verified in R0-03)
- **Bug:** A real bundled-JAR smoke test received colored log output where an LSP `Content-Length` header was required.
- **Invariant violated:** LSP transport contract: stdout must carry protocol messages only.
- **Root cause:** Logback console appender defaulted to stdout.
- **Fix:** Console target changed to `System.err`; rolling log retention bounded to 14 days; LSPLauncher builder configured with `.setRemoteInterface(GrailsLanguageClient)`.
- **Test:** `scripts/smoke-server.js` verified with code 0 in 1403 ms (SHA-256: `5B829F88B51443370BD00B0924D72CCBC4E65DBC2D70CEC58017F191B76A549A`).
- **Detected by:** Real JVM transport smoke test.
- **Date:** 2026-09-09.

### ST-003: Duplicate Source Directory Indexing via server/bin
- **Status:** Resolved
- **Bug:** VS Code reported 120 errors ("variable already in scope", "repetitive method name/signature") across Groovy classes.
- **Invariant violated:** `INV-STATE-002` single canonical source of truth for symbols.
- **Root cause:** `server/bin` directory contained a duplicated copy of Groovy source files generated by an external build, causing Groovy/Java compiler to index both `src/main/groovy` and `bin/main`.
- **Fix:** Removed `server/bin`, added `bin/` to `.gitignore`, added `"**/bin": true` to `.vscode/settings.json` `files.exclude`.
- **Test:** `.\gradlew.bat compileGroovy compileTestGroovy` passes with 0 errors.
- **Detected by:** VS Code Problems panel reporting 120 duplicate variable and method scope errors.
- **Date:** 2026-09-09.

### CC-001: Interrupted Background Compilation Migration
- **Status:** Resolved (verified in R0-01)
- **Bug:** Latest saved incremental suite had seven failures with `ProjectState` to `AtomicReference` cast errors. Three scheduler tests additionally used `.empty` map assertions that returned null for an empty map.
- **Invariant violated:** `INV-STATE-004` publication correctness and the regression-test gate.
- **Root cause:** Property access inside lock closure evaluated dynamically under `@CompileStatic`. Repaired with `this.@state.get()` and explicit map/queue tests.
- **Fix:** Restored static compilation in `ProjectContextImpl`; repaired scheduler assertions. All 7 incremental and 9 document compilation tests pass.
- **Test:** `GrailsIncrementalCompilerSpec` (7/7 pass); `DocumentCompilationSpec` (9/9 pass).
- **Detected by:** Targeted tests during migration from inline to queued document compilation.
- **Date:** 2026-09-09.

### CC-002: Unbounded Document Queue and Fairness Starvation
- **Status:** Resolved (R1-01)
- **Bug:** Large edit storms can flood worker queues with unbounded compilation jobs; lack of byte limits and multi-root fairness.
- **Invariant violated:** `INV-PERF-001`, `INV-PERF-002`, `INV-STATE-005`
- **Root cause:** Pre-R1 scheduler coalesces by URI but does not bound aggregate queue memory or guarantee cross-root fairness.
- **Fix:** The document service now stores bounded URI/version tickets, uses per-root lanes and round-robin dispatch, reads source only for the active candidate, and performs bounded fair overload recovery on the worker. Close tickets have priority; overflowed closes collapse to a reconstructable reconciliation marker. Root removal marks the context terminal immediately and releases it after active document work drains.
- **Test:** `DocumentCompilationSpec` covers blocked multi-root storms, count and byte bounds, latest-revision recovery, superseded completion barriers, oversized inputs, close storms and root cancellation. `WorkspaceLifecycleSpec` covers prompt removal during blocked work and prevents late publication.
- **Detected by:** Architecture audit.
- **Date:** 2026-09-09.

### CC-003: Concurrent Reader Visibility and AST Snapshot Isolation
- **Status:** Resolved (verified in R1-04)
- **Bug:** Shallow AST visitor copies retain mutable Groovy AST node object identities; concurrent readers during compile/remove could observe partial publication.
- **Invariant violated:** `INV-STATE-001`, `INV-STATE-004`, `INV-OWN-005`
- **Root cause:** Visitor `copyFrom` duplicates collection maps, not deep AST nodes.
- **Fix:** Providers decouple from compiler writer lock by reading immutable snapshots via `RequestContext.ast()` and `RequestContext.classLoader()`. `RequestLease` reference counting and `drainLeases()` ensure safe AST detachment via `DetachedASTAccessor.INSTANCE` on project hibernation and disposal (`INV-STATE-001`, `INV-STATE-004`, `INV-OWN-005`).
- **Test:** `PublicationAndLifecycleSpec` (7/7 passing: 20 concurrent readers across 50 continuous compilation commits with zero exceptions or torn reads).
- **Detected by:** Architecture review.
- **Related ADRs:** ADR-010
- **Date:** 2026-09-10.

### DP-001: Cross-Root Classpath Pollution and Guess-Based Project Edges
- **Status:** Resolved (verified in R2-01 and R2-03)
- **Bug:** Two workspace roots with differing dependency versions share an aggregated classloader; cross-project dependencies use name/prefix heuristics instead of resolved Gradle models.
- **Invariant violated:** `INV-DISC-001`, `INV-DISC-002`, `INV-OWN-008`, `INV-STATE-009`
- **Root cause:** Global classpath aggregation in compiler, lack of source-set scoping, and string name/prefix matching in `projectDependsOn`.
- **Fix:** R2-01 resolved classpath isolation per source set with `SourceSetModel`, `IsolatedParentClassLoader`, and generation-tracked scan publication. R2-03 resolved project dependencies via `ProjectDependencyEdge` extracted from Tooling API `IdeaModuleDependency` and `source-set-export.gradle`, canonical directory matching, and segment-safe classpath/output directory checks (`isSameOrChildPath`). Cross-project dirty propagation tracks origin and revision, terminates cycles via BFS visited sets, and dispatches asynchronously without nested writer locks (`INV-STATE-009`, `INV-OWN-008`).
- **Test:** `ClasspathAndSourceSetSpec` (11/11 passing), `SourceSetModelSpec` (7/7 passing), `DependencyInvalidationSpec` (8/8 passing), `PublicationAndLifecycleSpec` (7/7 passing).
- **Detected by:** Architecture review.
- **Related ADRs:** ADR-011
- **Date:** 2026-09-13.

### COMP-003: Multi-File Syntax Error Recovery Mismatch
- **Status:** Resolved (verified in R5-05)
- **Bug:** When multiple documents with trailing dot or syntax errors exist in the compilation unit simultaneously, error recovery checked global error collector line numbers without matching `sourceLocator` against `sourceUnit.name`, causing wrong line patching and broken AST recovery.
- **Invariant violated:** `INV-STATE-004`, `INV-STATE-005`
- **Root cause:** `SourceSetCompilationState.recoverFromSyntaxErrors` took first error from `compilationUnit.errorCollector` regardless of file origin, and blindly appended dummy completion tokens when line did not end with `.`.
- **Fix:** Filter syntax errors by matching `se.sourceLocator` against `sourceUnit.name`, verify candidate line ends with `.` before patching, fall back to scanning source lines for trailing dots, and cleanly close test documents after assertions.
- **Test:** `GroovyCompletionProviderSpec` (`should provide member access completions after dot`).
- **Detected by:** CI build on Ubuntu runner.
- **Date:** 2026-10-12.

### DP-002: Recursive Recompilation Ping-Pong in Mutually Dependent Projects
- **Status:** Resolved (verified in R5-05)
- **Bug:** Concurrent dependency invalidation between mutually dependent projects caused an infinite async recompilation storm and thread exhaustion on multi-project workspaces.
- **Invariant violated:** `INV-STATE-009`, `INV-OWN-008`
- **Root cause:** `ProjectContextImpl.recompileAsync` invoked `grailsService.workspaceManager?.propagateInvalidation(this)` upon completion, which triggered downstream recompilation of the upstream project, creating an unbounded recursive loop without atomic deduplication.
- **Fix:** Guarded `recompileAsync` with `AtomicBoolean isRecompilingAsync` to prevent duplicate concurrent runs, and removed circular `propagateInvalidation(this)` call from `recompileAsync` (transitive downstream invalidation is already handled by BFS in `WorkspaceManager.propagateInvalidation`). Added 10-minute test execution timeout in `build.gradle`.
- **Test:** `PublicationAndLifecycleSpec` (`R1-04/4: cross-project invalidation and concurrent compilation avoid deadlocks with deterministic lock ordering`).
- **Detected by:** GitHub Actions CI job timeout.
- **Date:** 2026-10-12.

### `PERF-001`: Memory Envelope Exceeded Under Full-Suite JVM Execution
- **Symptoms:** `PerformanceBaselineSpec > R1-05/1 & R1-05/2` failed with `settledHeap < 256L * 1024L * 1024L` (`323609936 false 268435456`) when executed within full 410-test suite in CI.
- **Root cause:** Initial heap in Gradle worker JVM accumulates state from prior 400 tests (~300 MiB), causing single-pass GC to measure background suite allocations rather than lifecycle leak. Absolute threshold (<256 MiB) assumed standalone JVM execution.
- **Fix:** Switched to 3-pass GC settling and asserted bounded growth delta (`(settledHeap - initialHeap) < 64 MiB`) alongside relaxed suite-wide envelope (`< 512 MiB`).
- **Test:** `PerformanceBaselineSpec` (`R1-05/1 & R1-05/2`).
- **Detected by:** CI build job failure.
- **Date:** 2026-10-12.

### `SYNC-001`: Superseded Sync Test Premature Poll Timeout and Mock Cache Collision
- **Symptoms:** `GradleSyncSpec > R1-03/1: superseded sync cancels in-flight work and applies newest configuration` intermittently failed with `ctx.project.name == "NewSyncResult"` on CI runners.
- **Root cause:** 
  1. `GradleService.getGrailsProjectAsync` cached and loaded projects via `ProjectCache` even when a custom `buildFunction` mock was injected, leading to stale binary disk cache hits returning `OldSyncResult` instead of invoking `buildFunction` for `NewSyncResult`.
  2. Cancellation on `timeoutFuture` did not explicitly propagate to the underlying `workerFuture`.
  3. Polling loop with fixed 5000ms timeout timed out before background worker thread finished callback for `secondFuture` under heavy runner load.
- **Fix:** Guarded `ProjectCache.load/save` with `buildFunction == null`, explicitly propagated `future.cancel(true)` on timeout/failure completion, invalidated test project cache via `forceInvalidation()`, explicitly awaited `secondFuture.get(10, TimeUnit.SECONDS)`, and increased poll timeout bound to 10s.
- **Test:** `GradleSyncSpec` (`R1-03/1`).
- **Detected by:** CI build job failure on `main`.
- **Date:** 2026-10-12.

---

## Failure Test Matrix — Use Before Every Feature

| Category | What To Test | Example |
|---|---|---|
| **Null/Empty** | Required fields absent | LSP request with no URI |
| **Invariant violations** | Contract broken | Provider writing to `visitor` |
| **External failure** | Dependency down | Gradle crash mid-sync |
| **State transition** | Out of order | Completion before `setupWorkspace()` |
| **Concurrency** | Simultaneous ops | Two completions + `didChange` |
| **Data mutation** | Mutable as key | AST node identity as cache key |
| **Resource exhaustion** | Limits exceeded | 50 rapid `didChange` events |
| **Cancellation** | Mid-op abort | `didClose` during completion |
| **Recovery** | Post-failure state | Provider state after OOM |

### How To Use

1. Give agent this matrix + feature spec BEFORE implementation
2. Agent generates one test per category
3. Review — extend with one case agent missed
4. Run tests — all should fail (not implemented yet)
5. Implement feature
6. Run tests — all should pass
