package kingsk.grails.lsp.providers.document

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import kingsk.grails.lsp.context.RequestContext
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.utils.ast.ASTUtils
import kingsk.grails.lsp.utils.ast.GrailsASTHelper
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import kingsk.grails.lsp.context.ProviderContext
import kingsk.grails.lsp.services.WorkspaceManager
import kingsk.grails.lsp.core.visitor.GrailsASTVisitor
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode

import java.util.concurrent.CompletableFuture

/**
 * Modern Rename Provider.
 */
@Slf4j
@CompileStatic
class GrailsRenameProvider extends BaseProvider {

    GrailsRenameProvider(ProviderContext providerContext, WorkspaceManager workspaceManager) {
        super(providerContext, workspaceManager)
    }

    CompletableFuture<WorkspaceEdit> provideRename(RenameParams params) {
        def textDocument = params.textDocument
        def documentURI = textDocument.uri
        def newName = params.newName

        def token = createCancellationToken(documentURI)
        long startTime = System.currentTimeMillis()

        return CompletableFuture.supplyAsync({ ->
            RequestContext ctx = null
            try {
                checkCancellation(token)
                ctx = createRequestContext(documentURI)

                def offsetNode = ctx.ast()?.getNodeAtPosition(documentURI, params.position)
                if (!offsetNode) {
                    return null
                }

                checkCancellation(token)
                def references = GrailsASTHelper.getReferences(offsetNode, (GrailsASTVisitor) ctx.ast(), params.position) ?: []

                def targetNode = GrailsASTHelper.getDefinition(offsetNode, true, (GrailsASTVisitor) ctx.ast()) ?: offsetNode
                List<ASTNode> targets = []
                if (targetNode && targetNode.lineNumber > 0) {
                    targets.add(targetNode)
                }
                for (ASTNode ref : references) {
                    if (ref && ref.lineNumber > 0 && !targets.any { it.is(ref) }) {
                        targets.add(ref)
                    }
                }

                WorkspaceEdit workspaceEdit = new WorkspaceEdit()
                workspaceEdit.changes = [:]
                targets.each { ASTNode node ->
                    def uri = ctx.ast()?.getURI(node) ?: documentURI.toString()
                    Range range = ASTUtils.astNodeToSelectionRange(node) ?: ASTUtils.astNodeToRange(node)
                    if (range) {
                        TextEdit textEdit = new TextEdit(range, newName)
                        if (!workspaceEdit.changes[uri]) {
                            workspaceEdit.changes[uri] = []
                        }
                        workspaceEdit.changes[uri] << textEdit
                    }
                }

                return workspaceEdit
            } finally {
                ctx?.close()
                recordHealth("rename", System.currentTimeMillis() - startTime, true)
            }
        })
    }

    private String getPartialNodeText(String uri, Object node) {
        // Implementation for getting node text
        ""
    }

    private TextEdit createTextEditToRenameClassNode(ClassNode node, String newName, String contents, Object range) {
        // Implementation
        null
    }

    private TextEdit createTextEditToRenameMethodNode(MethodNode node, String newName, String contents, Object range) {
        // Implementation
        null
    }

    private TextEdit createTextEditToRenamePropertyNode(PropertyNode node, String newName, String contents, Object range) {
        // Implementation
        null
    }
}
