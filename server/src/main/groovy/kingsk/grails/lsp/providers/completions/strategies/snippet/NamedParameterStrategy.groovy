package kingsk.grails.lsp.providers.completions.strategies.snippet

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import kingsk.grails.lsp.context.RequestContext
import kingsk.grails.lsp.model.enums.CompletionTarget
import kingsk.grails.lsp.providers.completions.BaseCompletionStrategy
import kingsk.grails.lsp.providers.completions.CompletionRequest
import kingsk.grails.lsp.utils.ast.GrailsASTHelper
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import kingsk.grails.lsp.core.visitor.GrailsASTVisitor

/**
 * Provides named parameter completions for Grails DSL methods like render, redirect, etc.
 */
@Slf4j
@CompileStatic
class NamedParameterStrategy extends BaseCompletionStrategy {

    @Override
    int getPriority() { return 87 }

    @Override
    CompletionTarget target() { return CompletionTarget.OFFSET }

    @Override
    boolean canHandle(CompletionRequest request, RequestContext ctx) {
        return getEnclosingMethodCall(request.offsetNode, ctx) != null
    }

    @Override
    List<CompletionItem> provideCompletions(CompletionRequest request, RequestContext ctx) {
        List<CompletionItem> completions = []
        MethodCallExpression methodCall = getEnclosingMethodCall(request.offsetNode, ctx)
        if (!methodCall) return completions

        def visitor = ctx.ast() as GrailsASTVisitor
        List<MethodNode> methods = GrailsASTHelper.getMethodOverloadsFromCallExpression(methodCall, visitor)

        if (!methods && request.isGrailsProject) {
             String name = methodCall.methodAsString
             if (name in ['render', 'redirect', 'respond', 'forward']) {
                 getGrailsDslParams(name).each { p ->
                     CompletionItem item = new CompletionItem(p)
                     item.kind = CompletionItemKind.Property
                     item.detail = "Named parameter"
                     item.insertText = "${p}: "
                     completions.add(item)
                 }
                 return completions
             }
        }

        methods.each { MethodNode method ->
            if (method.parameters) {
                method.parameters.each { Parameter param ->
                    CompletionItem item = new CompletionItem(param.name)
                    item.kind = CompletionItemKind.Property
                    item.detail = "Named parameter (${param.type.nameWithoutPackage})"
                    item.insertText = "${param.name}: "
                    completions.add(item)
                }
            }
        }

        return completions
    }

    private MethodCallExpression getEnclosingMethodCall(ASTNode node, RequestContext ctx) {
        ASTNode current = node
        Set<ASTNode> visited = new HashSet<>()
        while (current != null && visited.add(current)) {
            if (current instanceof ArgumentListExpression || current instanceof org.codehaus.groovy.ast.expr.TupleExpression) {
                ASTNode parent = ctx.ast().getParent(current)
                if (parent instanceof MethodCallExpression) {
                    return parent
                }
            }
            if (current instanceof MethodCallExpression) {
                return current
            }
            current = ctx.ast().getParent(current)
        }
        return null
    }

    private List<String> getGrailsDslParams(String methodName) {
        switch (methodName) {
            case 'render': return ['view', 'template', 'model', 'text', 'status', 'contentType', 'encoding']
            case 'redirect': return ['action', 'controller', 'id', 'params', 'url', 'uri', 'mapping']
            case 'forward': return ['action', 'controller', 'id', 'params']
            case 'respond': return ['view', 'model', 'status']
            default: return []
        }
    }
}
