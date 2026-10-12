package kingsk.grails.lsp.core.refactor

import groovy.transform.CompileStatic

@CompileStatic
class DocumentPrecondition implements Serializable {
    final String uri
    final Integer expectedVersion
    final String expectedHash

    DocumentPrecondition(String uri, Integer expectedVersion = null, String expectedHash = null) {
        this.uri = uri
        this.expectedVersion = expectedVersion
        this.expectedHash = expectedHash
    }

    Map<String, Object> toMap() {
        return [
            uri: uri,
            expectedVersion: expectedVersion,
            expectedHash: expectedHash
        ]
    }
}
