package kingsk.grails.lsp.core.check

import groovy.transform.CompileStatic

@CompileStatic
enum CheckStatus {
    SUCCESS("success"),
    FAILURE("failure"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout"),
    NO_TESTS("no_tests"),
    SKIPPED("skipped"),
    PARTIAL("partial")

    final String value

    CheckStatus(String value) {
        this.value = value
    }

    static CheckStatus fromString(String str) {
        if (!str) return null
        String normalized = str.trim().toLowerCase()
        return values().find { it.value == normalized }
    }
}
