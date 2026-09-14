# Client Status

> **AI AGENTS: Update this file on EVERY task that touches client code. Status only — no docs, no API, no architecture. Just current state.**
> Last updated: 2026-09-14

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
