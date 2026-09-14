package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
enum AnalysisOriginKind {
    DECLARATION("DECLARATION"),
    COMPILATION_UNIT("COMPILATION_UNIT"),
    CAPABILITY_ADAPTER("CAPABILITY_ADAPTER"),
    INDEX("INDEX"),
    UNKNOWN("UNKNOWN")

    private final String value

    AnalysisOriginKind(String value) {
        this.value = value
    }

    String getValue() {
        return value
    }

    static AnalysisOriginKind fromString(String str) {
        if (!str) return UNKNOWN
        for (AnalysisOriginKind o : values()) {
            if (o.value.equalsIgnoreCase(str)) return o
        }
        return UNKNOWN
    }

    @Override
    String toString() {
        return value
    }
}

