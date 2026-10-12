package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

/**
 * Lifecycle status of a change review.
 */
@CompileStatic
enum ChangeReviewStatus {
    READY("ready"),
    STALE_BUFFER("stale_buffer"),
    STALE_DEPENDENCY("stale_dependency"),
    CHECKS_FAILED("checks_failed"),
    PARTIAL_EDITS("partial_edits"),
    UNVERIFIED("unverified")

    final String jsonValue

    ChangeReviewStatus(String jsonValue) {
        this.jsonValue = jsonValue
    }

    String getValue() {
        return jsonValue
    }
}
