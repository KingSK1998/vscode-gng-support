package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class AnalysisRange implements Serializable {
    final AnalysisPosition start
    final AnalysisPosition end

    AnalysisRange(AnalysisPosition start, AnalysisPosition end) {
        this.start = start
        this.end = end
    }

    AnalysisRange(int startLine, int startChar, int endLine, int endChar) {
        this.start = new AnalysisPosition(startLine, startChar)
        this.end = new AnalysisPosition(endLine, endChar)
    }

    Map<String, Object> toMap() {
        return [
            start: start?.toMap(),
            end: end?.toMap()
        ]
    }
}

