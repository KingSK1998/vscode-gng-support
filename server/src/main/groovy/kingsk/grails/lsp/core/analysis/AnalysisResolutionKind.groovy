package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
enum AnalysisResolutionKind {
    DECLARATION_BACKED("declaration-backed"),
    FRAMEWORK_DERIVED("framework-derived"),
    RUNTIME_OBSERVED("runtime-observed"),
    HEURISTIC("heuristic"),
    UNRESOLVED("unresolved")

    private final String value

    AnalysisResolutionKind(String value) {
        this.value = value
    }

    String getValue() {
        return value
    }

    static AnalysisResolutionKind fromString(String str) {
        if (!str) return UNRESOLVED
        for (AnalysisResolutionKind k : values()) {
            if (k.value.equalsIgnoreCase(str)) return k
        }
        return UNRESOLVED
    }

    @Override
    String toString() {
        return value
    }
}

