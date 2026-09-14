package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class SymbolSearchRequest implements Serializable {
    String projectUri
    String query
    String uri
    String sourceSet
    List<String> kinds = []
    Integer limit
    Integer maxPayloadBytes
    String cursor

    SymbolSearchRequest() {}

    SymbolSearchRequest(String projectUri, String query) {
        this.projectUri = projectUri
        this.query = query
    }
}

