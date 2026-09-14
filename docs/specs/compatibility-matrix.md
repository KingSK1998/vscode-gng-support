# Tested Compatibility Matrix Specification

**Status:** Active specification for R2-06. **Updated:** 2026-09-14.
**Invariants:** `INV-DISC-001`, `INV-DISC-002`, `INV-DISC-003`.

The vscode-gng-support extension requires verified, reproducible compatibility evidence before claiming support for any Grails / Groovy runtime combination. In accordance with `AGENTS.md`, `docs/specs/library-discovery.md`, and `docs/specs/validation.md`, compatibility is determined by real artifact evidence and exact toolchain validation—never by family labels (e.g., "Grails 7+") or unchecked forward-compatibility assumptions.

---

## 1. Support Classifications

The runtime compatibility evaluation explicitly classifies any project into one of three distinct tiers (`kingsk.grails.lsp.core.compatibility.CompatibilityLevel`):

| Classification | Definition | IDE / LSP Behavior |
|---|---|---|
| `TESTED` | Fully pinned combinations with verified artifacts, exact toolchains (JDK, Gradle), SHA-256 content fingerprints, and real-resolution integration test fixtures. | Full capabilities enabled with highest confidence. Proven via integration checks. |
| `EXPERIMENTAL` | Unpinned minor/patch versions or newer releases sharing the major version (e.g., Grails 7.9.0, Grails 7.0.99). **Major version matching does NOT promote a version to `TESTED`.** | Core language server capabilities enabled with experimental status. Clear diagnostics and capability caveats reported. |
| `UNSUPPORTED` | Incompatible or legacy runtimes (Grails 2.x–6.x, Groovy 2.x/3.x, JDK < 17). | Explicit diagnostic warning/error emitted. IDE warns that capabilities are disabled or unreliable for legacy runtimes. |

---

## 2. Pinned Compatibility Matrix

The canonical executable matrix is registered in `kingsk.grails.lsp.core.compatibility.CompatibilityMatrix` and mirrored in fixture manifest `server/src/test/resources/compatibility/compatibility-matrix.json`.

| Entry ID | Grails Version | Groovy Version | JDK Version | Gradle Wrapper | Provenance / Fixture | Status |
|---|---|---|---|---|---|---|
| `grails-7.0.0-rc1` | `7.0.0-RC1` | `4.0.23` | `17` (min 17, max 21) | `8.12` | `server/src/test/resources/test-projects/grails-test-project` | `TESTED` |
| `grails-7.0.0-ga` | `7.0.0` | `4.0.24` | `17` (min 17, max 21) | `8.12` | Official Grails 7 GA Artifact Release | `TESTED` |

### Required Artifact Dependencies & Content Fingerprints

Every `TESTED` combination defines required core dependencies and content SHA-256 fingerprints to permit deterministic offline replay:

```json
{
  "dependencies": [
    "org.grails:grails-core",
    "org.grails:grails-bootstrap",
    "org.grails:grails-web",
    "org.grails.plugins:gorm"
  ],
  "artifactFingerprints": {
    "grails-core-7.0.0-RC1.jar": "8a35567b5b5de5e7e17ab2a0248ad95c1dfb704c7760920405232fb535a22830",
    "groovy-4.0.23.jar": "136b694b8e8fbc8d6268aa3ef180baecaaad7d7f7663e6ef847e70497520e58f"
  }
}
```

---

## 3. Dynamic Resolution and Isolation Rules

### 3.1 Multi-Root Independence with Version Skew
- Each project root in a multi-root workspace evaluates its compatibility independently against the matrix.
- Root A running Grails 7.0.0-RC1 evaluates to `TESTED`.
- Root B running Grails 7.9.0 evaluates to `EXPERIMENTAL`.
- Root C running Grails 6.2.0 evaluates to `UNSUPPORTED`.
- Cross-project contamination or default-project fallback is strictly prohibited (`INV-DISC-001`).

### 3.2 Dynamic Dependency Refresh
- Addition, removal, or alteration of build dependencies dynamically changes the compatibility assessment without restarting the server (`INV-DISC-002`).
- If required dependencies (e.g., `grails-core` or `gorm`) are removed or corrupted, the project status immediately degrades from `TESTED` to `EXPERIMENTAL` or `UNSUPPORTED`, with specific missing dependencies enumerated.

### 3.3 Offline Replay
- The LSP server can verify artifact SHA-256 content fingerprints offline (`CompatibilityMatrix.verifyOfflineArtifactFingerprints`) without triggering Gradle daemon builds or network requests.
- Fingerprints ensure cached artifacts match verified bits.

### 3.4 Real-Resolution Integration Check
- At least one real-resolution integration check is maintained against `test-projects/grails-test-project`, verifying that resolved `GradleModel` dependencies and project properties match the matrix rules under real execution.
