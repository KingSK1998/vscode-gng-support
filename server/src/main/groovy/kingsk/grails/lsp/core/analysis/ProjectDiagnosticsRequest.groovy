package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class ProjectDiagnosticsRequest implements Serializable {
    String projectUri
    String documentUri
    String severity
    Integer limit

    ProjectDiagnosticsRequest() {}

    ProjectDiagnosticsRequest(String projectUri, String documentUri = null, String severity = null, Integer limit = null) {
        this.projectUri = projectUri
        this.documentUri = documentUri
        this.severity = severity
        this.limit = limit
    }
}

