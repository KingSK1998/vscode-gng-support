package kingsk.grails.lsp.core.refactor

import groovy.transform.CompileStatic
import org.eclipse.lsp4j.Range

@CompileStatic
class TargetSymbol implements Serializable {
    final String name
    final String kind
    final String uri
    final Range range

    TargetSymbol(String name, String kind, String uri, Range range) {
        this.name = name
        this.kind = kind
        this.uri = uri
        this.range = range
    }

    Map<String, Object> toMap() {
        return [
            name: name,
            kind: kind,
            uri: uri,
            range: range ? [
                start: [line: range.start.line, character: range.start.character],
                end: [line: range.end.line, character: range.end.character]
            ] : null
        ]
    }
}
