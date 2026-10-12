package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class VerificationSuggestion implements Serializable {
    final String checkType
    final String target
    final String reason

    VerificationSuggestion(String checkType, String target, String reason) {
        this.checkType = checkType
        this.target = target
        this.reason = reason
    }

    Map<String, Object> toMap() {
        return [
            checkType: checkType,
            target: target,
            reason: reason
        ]
    }
}
