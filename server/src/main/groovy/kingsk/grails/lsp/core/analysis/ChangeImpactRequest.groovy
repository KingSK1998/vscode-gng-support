package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class ChangeImpactRequest implements Serializable {
    String projectUri
    List<String> changedUris = []
    List<String> changedSymbols = []
    Integer depth = 2
    Integer limit = 100
    Integer maxPayloadBytes = 65536
    String cursor

    ChangeImpactRequest() {}
}
