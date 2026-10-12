package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class ChangeEditsSummary {
    int totalFiles = 0
    int totalEdits = 0
    boolean hasCollisions = false
    List<String> files = []

    Map<String, Object> toMap() {
        return [
            totalFiles: totalFiles,
            totalEdits: totalEdits,
            hasCollisions: hasCollisions,
            files: files
        ]
    }
}
