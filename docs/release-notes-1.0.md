# Release Notes — Groovy and Grails Support for VS Code v1.0.0

## 1. Overview & Product Identity

**vscode-gng-support** is the premier Visual Studio Code extension engineered specifically for **Grails 7+** and **Groovy 4**, delivering deep conventional language intelligence, deterministic LSP architecture, and native AI Agent interoperability.

Unlike legacy tooling relying on brittle text scraping or requiring patched Grails distributions, `vscode-gng-support` connects VS Code to a purpose-built JVM Language Server that resolves compiler and AST facts directly from each project's isolated Gradle dependencies and snapshot lineage.

---

## 2. Core Capabilities & Highlights

### ⚡ Compiler & Lifecycle Foundation (R0, R1, R2)
- **Multi-Root Classpath & Source-Set Isolation (`R2-01`)**: Strict classpath isolation across roots and source sets (`main`, `test`, `integration-test`) preventing accidental cross-talk and visibility leakage.
- **Single-Snapshot Lineage & LKG Retention (`R1-03`, `R1-04`)**: Background AST compilation maintains the Last-Known-Good (LKG) snapshot during compilation errors, allowing responsive editor queries (<5ms latency) without blocking.
- **Coherent Dependency Refresh & Edge Resolution (`R2-02`, `R2-03`)**: Real Gradle Tooling API model export with Tooling API integration, detecting dependency additions, removals, and bytecode changes with content fingerprints.
- **Tested Compatibility Matrix (`R2-06`)**: Grounded against pinned Grails 7 / Groovy 4 distributions with SHA-256 artifact verification and explicit support tiers (`TESTED`, `EXPERIMENTAL`, `UNSUPPORTED`).

### 🧩 Grails Conventional Intelligence & Adapters (R2, R4)
- **Capability Adapters (`R2-05`)**:
  - **GORM Dynamic Finders & Criteria**: Auto-complete and explain `findBy*`, `findAllBy*`, `countBy*` (including boolean `And`/`Or` combinations up to 5 properties), domain properties (`id`, `version`, `errors`), and criteria restrictions.
  - **Injection & Web Scopes**: Implicit controller scopes (`params`, `request`, `session`, `flash`), action render arguments, service auto-wiring, and `Validateable` command objects.
  - **TagLib & Views**: TagLib namespaces (`g:` and custom tags) with tag attribute completion and metadata.
  - **Configuration Intelligence**: Convention-based resolution of `grailsApplication.config`.
- **Configuration & i18n Intelligence (`R4-04`)**: Unified multi-format parser supporting YAML, JSON, and Properties, environment block overrides (`environments.development.*`), placeholder substitution (`${PORT:8080}`), secret masking (`******`), and multi-locale message bundles.
- **GSP Embedded-Language Editing (`R4-03`)**: Transparent multi-language editing across HTML, CSS, JavaScript, and Groovy with 1:1 whitespace masking virtual documents and strict position mapping preventing invalid boundary edits (`INV-EDIT-001`).

### 🤖 Shared Typed Analysis Boundary & Agent Tools (R3, R5)
- **Zero Secondary Indexing (`INV-TOOL-001`)**: Both human IDE commands and AI agent tools query the identical in-memory language server models (`SnapshotManager`, `FileContentTracker`, `IndexSnapshot`), preventing divergence.
- **Language Model (LM) Tools (`R3-02`, `R5-01`, `R5-02`, `R5-03`, `R5-04`)**: Exposes 9 structured tools in VS Code Copilot / Chat:
  - `grails_project_capabilities`
  - `grails_symbols_search`
  - `grails_symbols_explain`
  - `grails_project_diagnostics`
  - `grails_relationships`
  - `grails_change_impact`
  - `grails_refactor_preview`
  - `grails_checks_run`
  - `grails_change_review`
- **Model Context Protocol (MCP) Server (`R3-04`)**: Standalone `grails-mcp-server` running over JSON-RPC 2.0 stdio, enabling external assistants (Claude Desktop, Cursor, CLI agents) to inspect Grails projects with bounded trust and schema validation.

### 🛡️ Refactoring, Impact, and Change Verification (R5)
- **Conservative Change Impact (`R5-01`)**: Graph traversal discovering downstream ripples across domain associations, controllers, services, views, and URL mappings, explicitly reporting dynamic unprovable constructs.
- **Version-Aware Rename Previews (`R5-02`)**: Preconditions verify document buffer versions before preview generation and application, detecting member collisions and preventing stale overwrites.
- **Scoped Compile and Test Operations (`R5-03`)**: Bounded execution of Gradle compile and test jobs with timeout clamping, byte limits, cancellation hooks, and structured test result differentiation.
- **Unified Change Review (`R5-04`)**: Connects edits, impact, test checks, and assumptions under a single `changeId` and `analysisRevision`. Invalidates stale reviews when buffer versions advance (`stale_buffer`), surfaces failed checks (`checks_failed`), and enforces honest safety verdicts (`SAFE_TO_APPLY`, `REQUIRES_ATTENTION`, `UNSAFE_TO_APPLY`, `STALE_REVIEW`). Never claims approval from generated prose (`INV-TOOL-002`).

---

## 3. Supported Compatibility Matrix

| Component | Tested Versions | Support Tier |
|---|---|---|
| **Grails** | 7.0.0-RC1, 7.0.0 GA | `TESTED` |
| **Groovy** | 4.0.23 | `TESTED` |
| **Java JDK** | 17, 21 | `TESTED` |
| **Gradle** | 8.12 | `TESTED` |
| **VS Code** | ^1.103.0 | `TESTED` |
| **Operating Systems** | Windows 10/11, Linux (Ubuntu/Debian), macOS | `TESTED` |

---

## 4. Key Invariants & Safety Contracts

- **`INV-TOOL-001`**: Exact Input Identities — Every analysis, impact, check, and review response carries `analysisRevision` and `projectUri` fingerprinting active state.
- **`INV-TOOL-002`**: Trust Boundaries & Honest Verdicts — Change safety verdicts require verified compile and test facts; prose alone never constitutes approval or safety status.
- **`INV-TOOL-003`**: Resource Budgets — All operations enforce defensive bounds (limits, payload byte budgets, timeouts) to prevent memory exhaustion or hanging queries.
- **`INV-DISC-003`**: Provable vs Dynamic Separation — Clear distinction between compiler-proven facts and dynamic unknowns (reflection, GSP tag attributes, runtime metaprogramming).
- **`INV-EDIT-001`**: Precondition Guarding — All edits declare expected document versions and hashes, rejecting stale application attempts.

---

## 5. Limitations & Recovery Guidance

1. **Dynamic Metaprogramming**:
   - Dynamic methods injected at runtime via Groovy metaClass cannot be statically guaranteed by the compiler. These are surfaced in `dynamicUnknowns` for explicit manual verification.
2. **Buffer Version Invalidation**:
   - If a buffer is edited after generating a rename preview or review, the review status transitions to `stale_buffer` with verdict `STALE_REVIEW`. Recovery: Re-run preview or check execution against the latest buffer version.
3. **Build File Changes**:
   - Changes to `build.gradle`, `settings.gradle`, or `libs.versions.toml` trigger automatic debounced invalidation. Recovery: Allow Gradle sync to complete before running scoped checks.

---

## 6. Installation & Verification

Package from source:
```sh
npm ci
npm run build
npm run test:client
npm run test:server
npm run package
```

Install into VS Code:
```sh
code --install-extension vscode-gng-support.vsix
```

