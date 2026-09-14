package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class SymbolSearchResponse implements Serializable {
    final int schemaVersion = 1
    String projectUri
    String analysisRevision
    String freshness
    String completeness
    List<AnalysisSymbolItem> items = []
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
            items: items.collect { it.toMap() },
            nextCursor: nextCursor,
            totalEstimated: totalEstimated,
            limitations: limitations
        ]
    }
}

