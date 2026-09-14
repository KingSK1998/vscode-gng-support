package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class SymbolExplainResponse implements Serializable {
    final int schemaVersion = 1
    String projectUri
    String documentUri
    String analysisRevision
    String freshness
    String completeness
    AnalysisSymbolItem symbol
    String documentation
    String declaringType
    String inheritedFrom
    List<AnalysisSymbolItem> relatedSymbols = []
    List<String> limitations = []

    Map<String, Object> toMap() {
        return [
            schemaVersion: schemaVersion,
            projectUri: projectUri,
            documentUri: documentUri,
            analysisRevision: analysisRevision,
            freshness: freshness,
            completeness: completeness,
            symbol: symbol?.toMap(),
            documentation: documentation,
            declaringType: declaringType,
            inheritedFrom: inheritedFrom,
            relatedSymbols: relatedSymbols.collect { it.toMap() },
            limitations: limitations
        ]
    }
}

