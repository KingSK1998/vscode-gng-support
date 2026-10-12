package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

import java.util.Collections

@CompileStatic
class GrailsRelationshipItem implements Serializable {
    final String id
    final String type
    final RelationshipEndpoint source
    final RelationshipEndpoint target
    final String origin
    final String resolution
    final AnalysisEvidence evidence
    final Map<String, Object> details

    GrailsRelationshipItem(
        String id,
        String type,
        RelationshipEndpoint source,
        RelationshipEndpoint target,
        String origin,
        String resolution,
        AnalysisEvidence evidence = null,
        Map<String, Object> details = null
    ) {
        this.id = id
        this.type = type
        this.source = source
        this.target = target
        this.origin = origin
        this.resolution = resolution
        this.evidence = evidence
        this.details = details != null ? Collections.unmodifiableMap(new HashMap<String, Object>(details)) : Collections.<String, Object>emptyMap()
    }

    String getOriginKind() {
        return origin
    }

    String getResolutionKind() {
        return resolution
    }

    Map<String, Object> toMap() {
        return [
            id: id,
            type: type,
            source: source?.toMap(),
            target: target?.toMap(),
            origin: origin,
            resolution: resolution,
            evidence: evidence?.toMap(),
            details: details
        ]
    }
}
