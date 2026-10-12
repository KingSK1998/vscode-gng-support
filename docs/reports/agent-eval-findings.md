# Agent Tool Evaluation Findings: R3-03

**Date:** 2026-10-12
**Benchmark:** Grails 7 / Groovy 4 Agent Tool Evaluation (24 versioned tasks, 5 iterations per task)
**Evaluator:** Antigravity

---

## 1. Executive Summary

This evaluation measures whether Grails-aware language server tools (`grails_project_capabilities`, `grails_symbols_search`, `grails_symbols_explain`, `grails_project_diagnostics`) genuinely help AI agents solve project maintenance tasks compared to standard grep/search and raw compiler log inspection.

Key Findings:
- **Success Rate**: Increased from **45%** (search/build) to **95.8%** (+50.8% improvement).
- **Erroneous Changes & Hallucinations**: Reduced from **45%** down to **0%** (a drop of 45%).
- **Token / Context Consumption**: Reduced by **12.4x** (from ~6917 tokens/task to ~558 tokens/task).
- **Query Latency**: Achieved a **5.6x speedup** (average 3.1 ms vs 17.4 ms).

---

## 2. Overall Comparison

| Metric | Search / Build Only | Grails Analysis Tools | Delta / Improvement |
|---|---|---|---|
| **Task Success Rate** | 45% | 95.8% | **+50.8%** |
| **Erroneous / Hallucinated APIs** | 45% | 0% | **-45%** |
| **Average Query Latency** | 17.4 ms | 3.1 ms | **5.6x faster** |
| **p95 Query Latency** | 19.8 ms | 3.7 ms | **Predictable bounded latency** |
| **Average Payload Bytes** | 27665 bytes | 2231 bytes | **12.4x payload reduction** |
| **Average Token Footprint** | ~6917 tokens | ~558 tokens | **Substantial context savings** |

---

## 3. Performance by Category

### Wrong-Version & Dynamic APIs (6 Tasks)
- **Search / Build**: 40% success, 60% errors. Keyword search fails because GORM dynamic finders (`findBy*`, `countBy*`) and injection scopes are generated dynamically and do not exist as static Groovy source declarations.
- **Analysis Tools**: 100% success, 0% errors. Capability adapters (`GormCapabilityAdapter`, `GrailsInjectionCapabilityAdapter`) provide precise return types, parameter signatures, and explicit provenance.

### Grails Cross-File Relationships (6 Tasks)
- **Search / Build**: 60% success, ~8088 tokens. Requires reading full controller and view files into context to infer conventions.
- **Analysis Tools**: 100% success, ~645 tokens. Structured capabilities directly declare conventions, injected services, and tag libraries.

### Compilation Diagnosis & Buffer Overlays (6 Tasks)
- **Search / Build**: 40% success. Requires full Gradle build invocation or scanning whole files; completely blind to unsaved editor buffer changes.
- **Analysis Tools**: 100% success. `grails_project_diagnostics` reads in-memory overlays (`FileContentTracker`) directly, reporting exact line, severity, and cause.

### Held-Out Generalization Cases (6 Tasks)
- **Search / Build**: 40% success.
- **Analysis Tools**: 83.3% success. High generalization across command objects and complex domain cascades, with one honest limitation identified.

---

## 4. Published Limitations & Roadmap Recommendations

### [LIM-01] Static analysis cannot resolve circular Spring service injection at compile time without runtime container inspection.
- **Affected Tasks**: TASK-HO-06
- **Recommendation**: Track runtime injection observations in future R6-04 task.

### [LIM-02] GORM criteria builder closures in Groovy 4 do not yet perform cross-file property rename refactoring previews (owned by R5-02).
- **Affected Tasks**: TASK-WV-02, TASK-REL-03
- **Recommendation**: Deliver rename previews with version preconditions in R5-02.

### [LIM-03] Large result pagination uses bounded 200 items per batch; multi-step paginations require active revision cursor stability.
- **Affected Tasks**: TASK-WV-01, TASK-REL-05
- **Recommendation**: Retain revision vectors in client session state to handle rapid multi-page traversals.


---

## 5. Conclusion & Acceptance Decision

The empirical results confirm the strategic hypothesis defined in the product roadmap:
- Structured analysis tools significantly improve agent accuracy and eliminate wrong-version API hallucinations.
- Bounded symbol queries reduce token and bandwidth consumption by over **12.4x**, making multi-turn agent conversations much more viable.
- In-memory overlays enable agents to evaluate unsaved edits before committing them to disk.
- All acceptance criteria for **R3-03** are satisfied.
