package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class ProjectDiagnosticsResponse implements Serializable {
    final int schemaVersion = 1
    String projectUri
    String analysisRevision
    String freshness
    String completeness
    List<AnalysisDiagnosticItem> diagnostics = []
    int totalCount
    List<String> limitations = []

    Map<String, Object> toMap() {
        return [
            schemaVersion: schemaVersion,
            projectUri: projectUri,
            analysisRevision: analysisRevision,
            freshness: freshness,
            completeness: completeness,
            diagnostics: diagnostics.collect { it.toMap() },
            totalCount: totalCount,
            limitations: limitations
        ]
    }
}

