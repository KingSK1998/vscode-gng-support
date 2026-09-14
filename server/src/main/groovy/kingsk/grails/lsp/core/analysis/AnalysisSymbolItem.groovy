package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class AnalysisSymbolItem implements Serializable {
    final String name
    final String kind
    final String containerName
    final String uri
    final AnalysisRange range
    final String origin
    final String resolution
    final String artifactCoordinates
    final String signature
    final AnalysisEvidence evidence

    AnalysisSymbolItem(
        String name,
        String kind,
        String uri,
        AnalysisRange range,
        String origin,
        String resolution,
        String containerName,
        String artifactCoordinates,
        String signature,
        AnalysisEvidence evidence
    ) {
        this.name = name
        this.kind = kind
        this.uri = uri
        this.range = range
        this.origin = origin
        this.resolution = resolution
        this.containerName = containerName
        this.artifactCoordinates = artifactCoordinates
        this.signature = signature
        this.evidence = evidence
    }

    AnalysisSymbolItem(
        String name,
        String kind,
        String uri,
        AnalysisRange range,
        String origin,
        String resolution
    ) {
        this(name, kind, uri, range, origin, resolution, null, null, null, null)
    }

    String getOriginKind() {
        return origin
    }

    String getResolutionKind() {
        return resolution
    }

    String getContainerDescriptor() {
        return containerName
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = [
            name: name,
            kind: kind,
            uri: uri,
            range: range?.toMap(),
            origin: origin,
            resolution: resolution
        ]
        if (containerName) map.containerName = containerName
        if (artifactCoordinates) map.artifactCoordinates = artifactCoordinates
        if (signature) map.signature = signature
        if (evidence) map.evidence = evidence.toMap()
        return map
    }
}
