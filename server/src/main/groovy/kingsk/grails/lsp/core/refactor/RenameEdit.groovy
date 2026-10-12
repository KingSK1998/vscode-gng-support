package kingsk.grails.lsp.core.refactor

import groovy.transform.CompileStatic
import org.eclipse.lsp4j.Range

@CompileStatic
class RenameEdit implements Serializable {
    final String uri
    final Integer documentVersion
    final Range range
    final String newText
    final String evidence
    final boolean isEmbeddedGsp

    RenameEdit(
        String uri,
        Integer documentVersion,
        Range range,
        String newText,
        String evidence = null,
        boolean isEmbeddedGsp = false
    ) {
        this.uri = uri
        this.documentVersion = documentVersion
        this.range = range
        this.newText = newText
        this.evidence = evidence
        this.isEmbeddedGsp = isEmbeddedGsp
    }

    Map<String, Object> toMap() {
        return [
            uri: uri,
            documentVersion: documentVersion,
            range: range ? [
                start: [line: range.start.line, character: range.start.character],
                end: [line: range.end.line, character: range.end.character]
            ] : null,
            newText: newText,
            evidence: evidence,
            isEmbeddedGsp: isEmbeddedGsp
        ]
    }
}
