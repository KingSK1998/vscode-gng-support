package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class AnalysisErrorResponse implements Serializable {
    final int schemaVersion = 1
    String error
    AnalysisErrorCode code
    String details

    AnalysisErrorResponse() {}

    AnalysisErrorResponse(String error, AnalysisErrorCode code, String details = null) {
        this.error = error
        this.code = code
        this.details = details
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = [
            schemaVersion: schemaVersion,
            error: error,
            code: code?.name()
        ]
        if (details) map.details = details
        return map
    }
}

