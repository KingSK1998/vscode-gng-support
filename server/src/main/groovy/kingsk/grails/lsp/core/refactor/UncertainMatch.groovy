package kingsk.grails.lsp.core.refactor

import groovy.transform.CompileStatic
import org.eclipse.lsp4j.Range

@CompileStatic
class UncertainMatch implements Serializable {
    final String uri
    final Range range
    final String reason
    final String risk

    UncertainMatch(String uri, Range range, String reason, String risk = "medium") {
        this.uri = uri
        this.range = range
        this.reason = reason
        this.risk = risk
    }

    Map<String, Object> toMap() {
        return [
            uri: uri,
            range: range ? [
                start: [line: range.start.line, character: range.start.character],
                end: [line: range.end.line, character: range.end.character]
            ] : null,
            reason: reason,
            risk: risk
        ]
    }
}
