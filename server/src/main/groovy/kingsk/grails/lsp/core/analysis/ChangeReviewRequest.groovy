package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic
import kingsk.grails.lsp.core.check.CheckExecutionResponse
import kingsk.grails.lsp.core.refactor.RenameEdit

@CompileStatic
class ChangeReviewRequest {
    String projectUri
    String changeId
    List<RenameEdit> edits
    ChangeImpactResponse impact
    CheckExecutionResponse checks
    List<String> assumptions
}

