package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
enum AnalysisCompleteness {
    COMPLETE("complete"),
    PARTIAL("partial"),
    UNAVAILABLE("unavailable")

    private final String value

    AnalysisCompleteness(String value) {
        this.value = value
    }

    String getValue() {
        return value
    }

    static AnalysisCompleteness fromString(String str) {
        if (!str) return UNAVAILABLE
        for (AnalysisCompleteness c : values()) {
            if (c.value.equalsIgnoreCase(str)) return c
        }
        return UNAVAILABLE
    }

    @Override
    String toString() {
        return value
    }
}

