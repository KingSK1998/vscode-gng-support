package kingsk.grails.lsp.core.refactor

import groovy.transform.CompileStatic

@CompileStatic
class RenameCollision implements Serializable {
    final String uri
    final String name
    final String conflictType
    final String description

    RenameCollision(String uri, String name, String conflictType, String description) {
        this.uri = uri
        this.name = name
        this.conflictType = conflictType
        this.description = description
    }

    Map<String, Object> toMap() {
        return [
            uri: uri,
            name: name,
            conflictType: conflictType,
            description: description
        ]
    }
}
