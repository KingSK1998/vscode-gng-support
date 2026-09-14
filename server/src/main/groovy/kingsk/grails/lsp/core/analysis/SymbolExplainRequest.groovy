package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class SymbolExplainRequest implements Serializable {
    String projectUri
    String documentUri
    String symbolName
    Integer line
    Integer character

    SymbolExplainRequest() {}

    SymbolExplainRequest(String projectUri, String documentUri, String symbolName = null, Integer line = null, Integer character = null) {
        this.projectUri = projectUri
        this.documentUri = documentUri
        this.symbolName = symbolName
        this.line = line
        this.character = character
    }
}

