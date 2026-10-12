package kingsk.grails.lsp.providers.document

import groovy.transform.CompileStatic
import kingsk.grails.lsp.context.ProviderContext
import kingsk.grails.lsp.context.RequestContext
import kingsk.grails.lsp.services.WorkspaceManager
import kingsk.grails.lsp.utils.position.RangeHelper
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either

import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

@CompileStatic
class GrailsInlayHintProvider extends BaseProvider {

    GrailsInlayHintProvider(ProviderContext providerContext, WorkspaceManager workspaceManager) {
        super(providerContext, workspaceManager)
    }

    CompletableFuture<List<InlayHint>> provideInlayHints(InlayHintParams params) {
        def token = createCancellationToken(params.textDocument.uri)
        long startTime = System.currentTimeMillis()

        Supplier<List<InlayHint>> supplier = ({
            RequestContext ctx = null
            try {
                checkCancellation(token)
                ctx = createRequestContext(params.textDocument.uri)
                String uri = params.textDocument.uri
                
                List<InlayHint> hints = []
                def nodes = ctx.ast()?.getNodes(uri)
                if (!nodes) return hints

                for (ASTNode node : nodes) {
                    if (node instanceof DeclarationExpression) {
                        DeclarationExpression decl = (DeclarationExpression) node
                        VariableExpression varExpr = decl.variableExpression
                        if (varExpr && decl.rightExpression && varExpr.isDynamicTyped()) {
                            ClassNode inferredType = decl.rightExpression.type
                            if (inferredType && inferredType.name != "java.lang.Object") {
                                String typeName = inferredType.nameWithoutPackage ?: inferredType.name
                                Position pos = new Position(varExpr.lineNumber - 1, varExpr.lastColumnNumber - 1)
                                if (params.range == null || RangeHelper.contains(params.range, pos)) {
                                    InlayHint hint = new InlayHint()
                                    hint.position = pos
                                    hint.label = Either.forRight([new InlayHintLabelPart(": " + typeName)])
                                    hint.kind = InlayHintKind.Type
                                    hint.data = [variableName: varExpr.name]
                                    hints.add(hint)
                                }
                            }
                        }
                    }
                }

                return hints
            } finally {
                ctx?.close()
                recordHealth("inlayHints", System.currentTimeMillis() - startTime, true)
            }
        } as Supplier<List<InlayHint>>)

        return CompletableFuture.supplyAsync(supplier)
    }
}
