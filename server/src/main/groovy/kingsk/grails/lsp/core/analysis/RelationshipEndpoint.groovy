package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class RelationshipEndpoint implements Serializable {
    String name
    String kind
    String uri
    AnalysisRange range
    String fqn
    String member

    RelationshipEndpoint() {}

    RelationshipEndpoint(String name, String kind, String uri = null, AnalysisRange range = null, String fqn = null, String member = null) {
        this.name = name
        this.kind = kind
        this.uri = uri
        this.range = range
        this.fqn = fqn
        this.member = member
    }

    Map<String, Object> toMap() {
        return [
            name: name,
            kind: kind,
            uri: uri,
            range: range?.toMap(),
            fqn: fqn,
            member: member
        ]
    }
}
