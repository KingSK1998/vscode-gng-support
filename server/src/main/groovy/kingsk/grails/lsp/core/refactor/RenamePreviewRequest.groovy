package kingsk.grails.lsp.core.refactor

import groovy.transform.CompileStatic
import org.eclipse.lsp4j.Position

@CompileStatic
class RenamePreviewRequest implements Serializable {
    String projectUri
    String documentUri
    Position position
    String newName
    String expectedRevision

    RenamePreviewRequest() {}
}
