package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class AnalysisEvidence implements Serializable {
    final String uri
    final String symbol
    final String adapterRule
    final String artifactCoordinates
    final String artifactFingerprint
    final String sourceSet

    AnalysisEvidence(
        String uri,
        String symbol,
        String adapterRule,
        String artifactCoordinates,
        String artifactFingerprint,
        String sourceSet
    ) {
        this.uri = uri
        this.symbol = symbol
        this.adapterRule = adapterRule
        this.artifactCoordinates = artifactCoordinates
        this.artifactFingerprint = artifactFingerprint
        this.sourceSet = sourceSet
    }

    AnalysisEvidence(String uri, String symbol) {
        this(uri, symbol, null, null, null, null)
    }

    AnalysisEvidence(String uri, String symbol, String adapterRule, String artifactCoordinates) {
        this(uri, symbol, adapterRule, artifactCoordinates, null, null)
    }

    String getRule() {
        return adapterRule
    }

    String getSourceUri() {
        return uri
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = [
            uri: uri,
            symbol: symbol
        ]
        if (adapterRule) map.adapterRule = adapterRule
        if (artifactCoordinates) map.artifactCoordinates = artifactCoordinates
        if (artifactFingerprint) map.artifactFingerprint = artifactFingerprint
        if (sourceSet) map.sourceSet = sourceSet
        return map
    }
}
