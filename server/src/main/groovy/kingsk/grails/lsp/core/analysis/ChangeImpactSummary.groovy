package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class ChangeImpactSummary {
    int impactedCount = 0
    int dynamicUnknownsCount = 0
    List<String> artifacts = []
    List<String> dynamicUnknowns = []

    Map<String, Object> toMap() {
        return [
            impactedCount: impactedCount,
            dynamicUnknownsCount: dynamicUnknownsCount,
            artifacts: artifacts,
            dynamicUnknowns: dynamicUnknowns
        ]
    }
}
