package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
enum AnalysisFreshness {
    CURRENT("current"),
    STALE("stale"),
    UNAVAILABLE("unavailable")

    private final String value

    AnalysisFreshness(String value) {
        this.value = value
    }

    String getValue() {
        return value
    }

    static AnalysisFreshness fromString(String str) {
        if (!str) return UNAVAILABLE
        for (AnalysisFreshness f : values()) {
            if (f.value.equalsIgnoreCase(str)) return f
        }
        return UNAVAILABLE
    }

    @Override
    String toString() {
        return value
    }
}

