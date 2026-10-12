package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class ChangeImpactItem implements Serializable {
    final String uri
    final String name
    final String kind
    final String impactReason
    final String confidence
    final String relationshipType
    final int depth
    final AnalysisEvidence evidence

    ChangeImpactItem(
        String uri,
        String name,
        String kind,
        String impactReason,
        String confidence,
        String relationshipType = null,
        int depth = 1,
        AnalysisEvidence evidence = null
    ) {
        this.uri = uri
        this.name = name
        this.kind = kind
        this.impactReason = impactReason
        this.confidence = confidence
        this.relationshipType = relationshipType
        this.depth = depth
        this.evidence = evidence
    }

    Map<String, Object> toMap() {
        return [
            uri: uri,
            name: name,
            kind: kind,
            impactReason: impactReason,
            confidence: confidence,
            relationshipType: relationshipType,
            depth: depth,
            evidence: evidence?.toMap()
        ]
    }
}
