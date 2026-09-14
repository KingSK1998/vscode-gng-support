package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
enum AnalysisErrorCode {
    INVALID_INPUT,
    UNAVAILABLE,
    CANCELLED,
    TIMEOUT,
    CURSOR_EXPIRED,
    INTERNAL_ERROR
}

