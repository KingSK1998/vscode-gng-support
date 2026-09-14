package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class AnalysisPosition implements Serializable {
    final int line
    final int character

    AnalysisPosition(int line, int character) {
        this.line = line
        this.character = character
    }

    Map<String, Object> toMap() {
        return [line: line, character: character]
    }
}

