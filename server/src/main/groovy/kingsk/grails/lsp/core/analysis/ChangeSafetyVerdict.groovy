package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

/**
 * Objective safety verdict for a proposed change review.
 * Invariant: Never claim approval or safe-change status merely from generated prose.
 */
@CompileStatic
enum ChangeSafetyVerdict {
    SAFE_TO_APPLY("SAFE_TO_APPLY"),
    REQUIRES_ATTENTION("REQUIRES_ATTENTION"),
    UNSAFE_TO_APPLY("UNSAFE_TO_APPLY"),
    STALE_REVIEW("STALE_REVIEW")

    final String jsonValue

    ChangeSafetyVerdict(String jsonValue) {
        this.jsonValue = jsonValue
    }

    String getValue() {
        return jsonValue
    }
}
