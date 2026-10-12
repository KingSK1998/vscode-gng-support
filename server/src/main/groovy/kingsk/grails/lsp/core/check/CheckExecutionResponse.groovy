package kingsk.grails.lsp.core.check

import groovy.transform.CompileStatic

@CompileStatic
class CheckExecutionResponse implements Serializable {
    final int schemaVersion = 1
    String checkId
    String projectUri
    String analysisRevision
    String checkType
    String status
    long startTime
    long endTime
    long durationMs
    List<String> tasksExecuted = []
    CheckSummary summary = new CheckSummary()
    List<CheckDiagnosticItem> diagnostics = []
    String output = ""
    List<String> artifactLocations = []
    List<String> limitations = []

    CheckExecutionResponse() {}

    Map<String, Object> toMap() {
        return [
            schemaVersion: schemaVersion,
            checkId: checkId,
            projectUri: projectUri,
            analysisRevision: analysisRevision,
            checkType: checkType,
            status: status,
            startTime: startTime,
            endTime: endTime,
            durationMs: durationMs,
            tasksExecuted: tasksExecuted,
            summary: summary?.toMap(),
            diagnostics: diagnostics ? diagnostics.collect { it.toMap() } : [],
            output: output,
            artifactLocations: artifactLocations,
            limitations: limitations
        ]
    }
}
