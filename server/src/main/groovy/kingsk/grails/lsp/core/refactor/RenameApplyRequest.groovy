package kingsk.grails.lsp.core.refactor

import groovy.transform.CompileStatic

@CompileStatic
class RenameApplyRequest implements Serializable {
    String previewId
    String projectUri
    List<DocumentPrecondition> documentPreconditions = []
    List<RenameEdit> edits = []

    RenameApplyRequest() {}
}
