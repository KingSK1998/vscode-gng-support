package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class AnalysisOperationException extends RuntimeException {
    final AnalysisErrorCode errorCode
    final String details

    AnalysisOperationException(AnalysisErrorCode errorCode, String message, String details = null) {
        super(message)
        this.errorCode = errorCode
        this.details = details
    }

    AnalysisErrorResponse toResponse() {
        return new AnalysisErrorResponse(message, errorCode, details)
    }
}

