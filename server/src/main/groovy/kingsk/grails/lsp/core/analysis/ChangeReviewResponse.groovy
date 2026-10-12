package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic
import kingsk.grails.lsp.core.refactor.RenameEdit

@CompileStatic
class ChangeReviewResponse {
    int schemaVersion = 1
    String changeId
    String projectUri
    String analysisRevision
    String freshness // "current" | "stale" | "invalidated"
    ChangeReviewStatus status
    ChangeSafetyVerdict safetyVerdict
    ChangeEditsSummary editsSummary
    List<RenameEdit> edits = []
    ChangeImpactSummary impactSummary
    ChangeChecksSummary checksSummary
    List<String> uncheckedAssumptions = []
    List<String> recoveryActions = []
    List<String> limitations = []

    Map<String, Object> toMap() {
        return [
            schemaVersion: schemaVersion,
            changeId: changeId,
            projectUri: projectUri,
            analysisRevision: analysisRevision,
            freshness: freshness,
            status: status != null ? status.value : null,
            safetyVerdict: safetyVerdict != null ? safetyVerdict.value : null,
            editsSummary: editsSummary != null ? editsSummary.toMap() : null,
            edits: edits ? edits.collect { it.toMap() } : [],
            impactSummary: impactSummary != null ? impactSummary.toMap() : null,
            checksSummary: checksSummary != null ? checksSummary.toMap() : null,
            uncheckedAssumptions: uncheckedAssumptions,
            recoveryActions: recoveryActions,
            limitations: limitations
        ]
    }
}
