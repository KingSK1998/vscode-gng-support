package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class AnalysisDiagnosticItem implements Serializable {
    final String documentUri
    final AnalysisRange range
    final String severity
    final String message
    final String source
    final String code

    AnalysisDiagnosticItem(
        String documentUri,
        AnalysisRange range,
        String severity,
        String message,
        String source = null,
        String code = null
    ) {
        this.documentUri = documentUri
        this.range = range
        this.severity = severity
        this.message = message
        this.source = source
        this.code = code
    }

    String getUri() {
        return documentUri
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = [
            documentUri: documentUri,
            range: range?.toMap(),
            severity: severity,
            message: message
        ]
        if (source) map.source = source
        if (code) map.code = code
        return map
    }
}

