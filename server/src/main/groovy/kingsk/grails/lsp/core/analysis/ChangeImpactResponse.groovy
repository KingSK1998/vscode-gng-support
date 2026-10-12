package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class ChangeImpactResponse implements Serializable {
    final int schemaVersion = 1
    String projectUri
    String analysisRevision
    String freshness
    String completeness
    List<String> changedUris = []
    List<ChangeImpactItem> impactedItems = []
    List<DynamicUnknownItem> dynamicUnknowns = []
    List<VerificationSuggestion> verificationSuggestions = []
    String nextCursor
    int totalEstimated
    List<String> limitations = []

    int getTotalCount() {
        return totalEstimated
    }

    boolean isHasMore() {
        return nextCursor != null
    }

    boolean getHasMore() {
        return nextCursor != null
    }

    Map<String, Object> toMap() {
        return [
            schemaVersion: schemaVersion,
            projectUri: projectUri,
            analysisRevision: analysisRevision,
            freshness: freshness,
            completeness: completeness,
            changedUris: changedUris,
            impactedItems: impactedItems ? impactedItems.collect { it.toMap() } : [],
            dynamicUnknowns: dynamicUnknowns ? dynamicUnknowns.collect { it.toMap() } : [],
            verificationSuggestions: verificationSuggestions ? verificationSuggestions.collect { it.toMap() } : [],
            nextCursor: nextCursor,
            totalEstimated: totalEstimated,
            limitations: limitations
        ]
    }
}
