package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class GrailsRelationshipsRequest implements Serializable {
    String projectUri
    String sourceUri
    String targetUri
    String uri
    List<String> types = []
    Integer depth
    String direction = "both"
    Integer limit
    Integer maxPayloadBytes
    String cursor

    GrailsRelationshipsRequest() {}
}
