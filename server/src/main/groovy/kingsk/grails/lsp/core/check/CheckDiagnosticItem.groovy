package kingsk.grails.lsp.core.check

import groovy.transform.CompileStatic
import kingsk.grails.lsp.core.analysis.AnalysisRange

@CompileStatic
class CheckDiagnosticItem implements Serializable {
    String uri
    AnalysisRange range
    String severity
    String message

    CheckDiagnosticItem() {}

    CheckDiagnosticItem(String uri, AnalysisRange range, String severity, String message) {
        this.uri = uri
        this.range = range
        this.severity = severity
        this.message = message
    }

    Map<String, Object> toMap() {
        return [
            uri: uri,
            range: range?.toMap(),
            severity: severity,
            message: message
        ]
    }
}
