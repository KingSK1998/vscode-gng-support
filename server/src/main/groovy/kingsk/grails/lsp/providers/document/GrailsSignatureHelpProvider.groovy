package kingsk.grails.lsp.providers.document

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import kingsk.grails.lsp.context.RequestContext
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.utils.ast.ASTUtils
import kingsk.grails.lsp.utils.ast.GrailsASTHelper
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.MethodCall
import org.eclipse.lsp4j.*
import kingsk.grails.lsp.context.ProviderContext
import kingsk.grails.lsp.services.WorkspaceManager
import kingsk.grails.lsp.model.enums.DocumentationType
import kingsk.grails.lsp.utils.diagnostics.DocumentationHelper
import kingsk.grails.lsp.core.visitor.GrailsASTVisitor

import java.util.concurrent.CompletableFuture

@Slf4j
@CompileStatic
class GrailsSignatureHelpProvider extends BaseProvider {

    GrailsSignatureHelpProvider(ProviderContext providerContext, WorkspaceManager workspaceManager) {
        super(providerContext, workspaceManager)
    }

    CompletableFuture<SignatureHelp> provideSignatureHelp(TextDocumentIdentifier textDocument, Position position) {
        def token = createCancellationToken(textDocument.uri)
        long startTime = System.currentTimeMillis()

        return CompletableFuture.supplyAsync {
            RequestContext ctx = null
            try {
                checkCancellation(token)
                ctx = createRequestContext(textDocument.uri)
                def offset = getNodeAtPosition(ctx, position)

                if (!offset) {
                    return null
                }

                // Climb up to find method call
                ASTNode current = offset
                MethodCall methodCall = null
                int depth = 0
                while (current != null && depth < 100) {
                    if (current instanceof MethodCall) {
                        methodCall = current as MethodCall
                        break
                    }
                    current = ctx.ast()?.getParent(current)
                    depth++
                }

                if (!methodCall) {
                    return null
                }

                checkCancellation(token)
                def methods = GrailsASTHelper.getMethodOverloadsFromCallExpression(methodCall, (GrailsASTVisitor) ctx.ast())

                List<SignatureInformation> information = methods.collect { MethodNode method ->
                    def label = ASTUtils.astNodeToName(method)
                    def documentation = DocumentationHelper.getDocumentation(method, ctx.grailsProject()?.isGrailsProject ?: false, (GrailsASTVisitor) ctx.ast(), DocumentationType.HOVER)
                    def parameters = method.parameters.collect { Parameter param ->
                        def paramName = ASTUtils.astNodeToName(param)
                        def paramDocs = DocumentationHelper.getDocumentation(method, ctx.grailsProject()?.isGrailsProject ?: false, (GrailsASTVisitor) ctx.ast(), DocumentationType.HOVER)
                        new ParameterInformation(paramName, paramDocs?.value)
                        new ParameterInformation(paramName, paramDocs)
                    }
                    new SignatureInformation(label, documentation?.value, parameters)
                    new SignatureInformation(label, documentation, parameters)
                }

                int activeParamIndex = getActiveParameter(methodCall, position)
                def bestMethod = GrailsASTHelper.getMethodFromCallExpression(methodCall, (GrailsASTVisitor) ctx.ast(), activeParamIndex)

                return new SignatureHelp(information, (Integer) methods.indexOf(bestMethod), activeParamIndex)
            } finally {
                ctx?.close()
                recordHealth("signatureHelp", System.currentTimeMillis() - startTime, true)
            }
        }
    }

    private int getActiveParameter(MethodCall methodCall, Position position) {
        0
    }
}
