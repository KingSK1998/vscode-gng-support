package kingsk.grails.lsp.core.refactor

import groovy.transform.CompileStatic

@CompileStatic
class RenameApplyResponse implements Serializable {
    final int schemaVersion = 1
    boolean applied = false
    int appliedEditsCount = 0
    int rejectedEditsCount = 0
    List<Map<String, String>> rejectedDetails = []
    String recoveryMessage

    Map<String, Object> toMap() {
        return [
            schemaVersion: schemaVersion,
            applied: applied,
            appliedEditsCount: appliedEditsCount,
            rejectedEditsCount: rejectedEditsCount,
            rejectedDetails: rejectedDetails,
            recoveryMessage: recoveryMessage
        ]
    }
}
