# Client Status

> **AI AGENTS: Update this file on EVERY task that touches client code. Status only — no docs, no API, no architecture. Just current state.**
> Last updated: 2026-10-12

---

## Extension Info

- **Version**: 0.2.0
- **Language**: TypeScript (strict mode)
- **VS Code Engine**: ^1.103.0
- **Node**: 20.x

---

## Feature Status

### ✅ Done & Working

| Feature | Notes |
|---|---|
| Verify and prepare the 1.0 release (R5-05) | ✅ Production verification pipeline executed: fat JAR built and copied (`grails-language-server-current-all.jar`), clean VSIX package created (`vscode-gng-support.vsix`, 30 files, 18.31 MB) with zero disallowed source/test/map files (`package.test.js`), stdio smoke test passed (1075ms initialization), all 7 performance scorecard criteria met (`reports/performance-baseline.json`), agent evaluation benchmark passed (95.8% success, 12.4x token reduction in `reports/agent-eval-report.json`), and comprehensive 1.0 release notes published (`docs/release-notes-1.0.md`). |
| Present human and agent change reviews (R5-04) | ✅ `AnalysisToolService.getChangeReview`, `ChangeReviewUseCase`, command `grails.analysis.changeReview`, LM tool `grails_change_review`, MCP tool `grails_change_review`, and `ChangeReviewPresenter.ts`. Unifies edits, source evidence, change impact ripples, executed scoped checks, and unchecked assumptions under a single stable `changeId` and `analysisRevision`. Detects and surfaces buffer version advance (`stale_buffer`), dirty dependency invalidation (`stale_dependency`), failed checks (`checks_failed`), and dynamic unknown verification requirements with honest safety verdicts (`SAFE_TO_APPLY`, `REQUIRES_ATTENTION`, `UNSAFE_TO_APPLY`, `STALE_REVIEW`) and actionable recovery (`INV-TOOL-001/002/003`, `INV-DISC-003`). Unit tests in `changeReview.unit.test.ts`. |
| Scoped compile and test operations (R5-03) | ✅ `AnalysisToolService.runChecks`, `grails.checks.run` command, `grails_checks_run` LM and MCP tools. Enforces structured parameter bounds, timeout clamping (1s-300s), output byte capping (1KB-256KB), input revision fingerprinting (`rev:<snap>:dep:<dep>`), check status differentiation (`success`, `failure`, `no_tests`, `skipped`, `partial`), and narrow test disclaimer limitations (`INV-TOOL-001/002/003`, `INV-PERF-002`). Unit tests in `checksRun.unit.test.ts`. |
| Version-aware rename previews (R5-02) | ✅ RefactorRenameService, AnalysisToolService.getRenamePreview and applyRename, commands `grails.refactor.preview` and `grails.refactor.apply`, and `grails_refactor_preview` LM and MCP tools. Enforces buffer version preconditions, rejects stale edits with recovery messages, detects member collisions, surfaces dynamic GORM finder uncertain matches, and applies edits via `vscode.WorkspaceEdit` (`INV-KEY-003`, `INV-TOOL-002`, `INV-EDIT-001`). Passing unit tests in `renamePreview.unit.test.ts`. |
| Conservative change impact (R5-01) | ✅ Client protocol types in `analysis.ts`, `AnalysisToolService.getChangeImpact`, `grails.analysis.getChangeImpact` command, and `grails_change_impact` LM and MCP tools. Enforces bounded parameters, workspace trust, structured downstream ripple detection, explicit dynamic unknowns, test suggestions, and pagination cursors (`INV-DISC-003`, `INV-TOOL-001/003`). Passing unit tests in `changeImpact.unit.test.ts`. |
| Views and accessibility (R4-07) | ✅ Dashboard, dependency graph, and GORM SQL preview integrated with actual project model facts, clear origin disclosures, accurate static DDL simulation labels, CSP and nonce enforcement, zero inline handlers, table view for screen readers, reduced-motion compliance, and node bounding (`INV-OWN-003/004`, `INV-DISC-003`, `INV-PERF-001/002`). 7 unit tests passing in `viewsAndAccessibility.unit.test.ts`. |
| Run, debug, and test workflows (R4-06) | ✅ Workspace trust checks across task execution, JVM debug sessions, and test controller. Full GradleService execution engine with dynamic port conflict detection, JVM debug socket polling, real GrailsTestService execution via `--tests` flags, and process cancellation/cleanup (`INV-OWN-003/004`, `INV-TOOL-002`, `INV-PERF-002`). 7 unit tests in `runDebugTestWorkflows.unit.test.ts`. |
| Configuration & i18n intelligence (R4-04) | ✅ Client document selectors updated for `yaml` and `json`, file watchers registered for configuration (`application*.{yml,yaml,properties,json}`) and i18n (`**/*.{groovy,gsp,properties,yml,yaml,json}`). Tested in `configIntelligence.unit.test.ts`. |
| GSP embedded-language editing (R4-03) | ✅ Embedded editing across HTML/CSS/JS/Groovy via virtual documents (`VirtualDocumentManager`), 1:1 whitespace masking, boundary edit validation (`GspPositionMapper`), TagLib completions (`GspEmbeddedLanguageService`). 7 unit tests in `gspEmbeddedService.unit.test.ts`. |
| Model Context Protocol (MCP) adapter (R3-04) | ✅ `GrailsMcpServer` exposing 4 typed analysis operations (`grails_project_capabilities`, `grails_symbols_search`, `grails_symbols_explain`, `grails_project_diagnostics`) over JSON-RPC 2.0 stdio, boundary validation, explicit `diskFacts` availability labeling, cancellation tokens, and disconnect cleanup (`INV-TOOL-001/003`). 18 client unit tests passing. |
| VS Code agent reads & Language Model Tools (R3-02) | ✅ `AnalysisToolService` registering 4 language model tools (`grails_project_capabilities`, `grails_symbols_search`, `grails_symbols_explain`, `grails_project_diagnostics`), 4 editor commands (`grails.analysis.*`), bounds capping, trust/scope validation (`INV-TOOL-001/003`), constructor injection (`INV-OWN-003`). 14 client unit tests passing. |
| Analysis operations protocol types | `client/src/shared/protocol/analysis.ts` matching JSON schema (R3-01) |
| Groovy syntax highlighting | TextMate grammar |
| GSP syntax highlighting | TextMate grammar |
| Groovy code snippets | Controller, Service, Domain, Test templates |
| GSP code snippets | Forms, tags, layouts, i18n |
| Auto-closing pairs / bracket matching | Groovy + GSP configs |
| Comment toggling | Both languages |
| LSP client integration | `vscode-languageclient`, connects to Grails Language Server |
| Language Server lifecycle | `LanguageServerManager` handles start/stop/restart |
| Bundled server startup | ✅ Default production launch; configured Java path, JAVA_HOME fallback, quote stripping, paths with spaces; separate app/development LSP ports; deterministic runtime JAR |
| Initial project notifications | ✅ Handlers registered before client startup; regression test verifies early discovery delivery |
| Build configuration watchers | ✅ Kotlin/Groovy build and settings files, Gradle properties and version catalogs (*.versions.toml) |
| Headless startup, packaging, and bundle tests | ✅ Seven tests executed via `npm run test:client`; includes vsce package asset verification, sourcemap exclusion, and entry point checks |
| Project Explorer (tree view) | Shows Controllers, Services, Domains |
| Status bar indicator | Extension state display |
| `grails.run` command | Runs Grails app via terminal |
| `grails.test` command | Runs tests |
| `grails.clean` command | Cleans project |
| `grails.compile` command | Compiles project |
| `grails.createArtifact` command | Wizard for Controller/Domain/Service/TagLib creation |
| `grails.setupWorkspace` command | Workspace auto-configuration |
| `grails.restartServer` command | Restarts LSP server |
| `grails.runGradleTask` command | Runs arbitrary Gradle tasks |
| GradleService integration | Hooks into VS Code Gradle extension API |
| ErrorService | Centralized error handling, severity levels |
| Grails project detection | Checks for `grails-app/` directory |
| Configuration settings | `grails.path`, `grails.javaHome`, `grailsLsp.*` |
| Dev mode LSP connection | Connects to remote server on port 5007 |
| Production mode LSP connection | Uses `client/server/grails-language-server-current-all.jar` |
| Core Architecture & Performance | Optimizations (ISSUE-001 through ISSUE-021). See history section below. |

### 🟡 In Progress

| Feature | Notes |
|---|---|
| `grails.runGradleTask` UI | Command wired; picker UI needs polish |

### 🔴 Known Issues / Broken

| Issue | Notes |
|---|---|
| Multi-root workspace | Server project contexts exist, but classpath isolation and end-to-end multi-project acceptance remain incomplete |

### ⬜ Planned / Future

| Feature | Notes |
|---|---|
| Multi-root workspace support | Finish server isolation, dependency refresh and packaged-editor acceptance tests |
| Grails plugin marketplace listing | Extension not yet published |
| Enhanced project explorer | More artifact types (Jobs, TagLibs, etc.) |

---

## Build Health

| Check | Status |
|---|---|
| TypeScript compile | ✅ Clean |
| ESLint | ✅ Clean |
| Headless tests | ✅ Seven startup, packaging, and bundle tests passing (2026-09-09) |
| Packaged-editor integration tests | ✅ Local VSIX install and packaged server smoke test passed (2026-09-09) |
| esbuild bundle | ✅ OK |

---

## Historical Consolidations (ISSUE-001 to ISSUE-021)

### Service Container & Lifecycle
- **ISSUE-001**: Lazy service initialization (core services immediately, others on first access).
- **ISSUE-017**: Added initialization promise to `ServiceContainer` (async initialize, `getInstanceAsync`).
- **ISSUE-018**: Circular dependency detection via `_initializingServices` tracking.
- **ISSUE-004**: Activation timeout wrapper (30s) with user notification.

### Webview & UI Services
- **ISSUE-002**: Webview services created on-demand in Commands, not at activation.
- **ISSUE-014**: Removed `retainContextWhenHidden`, use `WebviewStateManager` for proper state preservation.
- **ISSUE-015**: Bundled D3.js locally for offline support.
- **ISSUE-016**: Comprehensive webview cleanup on dispose (cancel requests, clear state).

### Event Bus & Messaging
- **ISSUE-006**: EventBus async handler execution with error isolation.
- **ISSUE-009**: EventBus listener limits (100 max) and accumulation warning.
- **ISSUE-010**: ActivationManager listener cleanup verification logging.

### Performance & Async File Operations
- **ISSUE-003**: `ProjectService` async file operations (`fs/promises`, `Promise.all` for parallel operations).
- **ISSUE-005**: Synchronous file reading converted to async in `ProjectService`.
- **ISSUE-007**: Made `GradleService.sync()` non-blocking, added `waitForSync()`.
- **ISSUE-008**: File watcher memory leak fixed with proper `Set`/`Map` tracking.
- **ISSUE-011**: LSP requests debounced (300ms) to prevent server flooding.
- **ISSUE-012**: Added `CancellationToken` support to LSP requests.
- **ISSUE-013**: Added batch test discovery with fallback to individual requests.

### Code Organization & Refactoring
- **ISSUE-019**: `Commands.ts` split into `UICommands`, `ProjectCommands`, `GrailsTaskCommands`, etc.
- **ISSUE-020**: LSP handlers organized in `services/lsp/handlers/` with `LspHandlerRegistry`.
- **ISSUE-021**: Moved `ProjectMapper` to `shared/`, removed `features/projects/` and `services/useCase/`.
