package kingsk.grails.lsp.providers.completions.strategies.context

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import kingsk.grails.lsp.context.ASTAccessor
import kingsk.grails.lsp.context.RequestContext
import kingsk.grails.lsp.core.capability.GormCapabilityAdapter
import kingsk.grails.lsp.model.enums.CompletionTarget
import kingsk.grails.lsp.providers.completions.BaseCompletionStrategy
import kingsk.grails.lsp.providers.completions.CompletionRequest

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind

/**
 * Handles completions for Closure Delegates inside DSLs, e.g., obj.with { | } or Criteria builders.
 */
@Slf4j
@CompileStatic
class ClosureDelegateStrategy extends BaseCompletionStrategy {

    @Override
    int getPriority() { return 65 }

    @Override
    boolean canHandle(CompletionRequest request, RequestContext ctx) {
        return getEnclosingClosureMethodCall(request.offsetNode, ctx.ast()) != null ||
               getEnclosingClosureField(request.offsetNode, ctx.ast()) != null
    }

    @Override
    List<CompletionItem> provideCompletions(CompletionRequest request, RequestContext ctx) {
        List<CompletionItem> completions = []
        MethodCallExpression methodCall = getEnclosingClosureMethodCall(request.offsetNode, ctx.ast())
        if (methodCall) {
            if (isCriteriaMethodCall(methodCall)) {
                completions.addAll(GormCapabilityAdapter.INSTANCE.getCriteriaCompletions())
                return completions
            }
            ClassNode delegateType = resolveDelegateType(methodCall, ctx)
            if (delegateType) {
                log.debug("Found closure delegate type from method call: ${delegateType.name}")
                VariableExpression dummyObj = new VariableExpression("it", delegateType)
                addMemberCompletions(dummyObj, ctx, completions)
            }
            return completions
        }

        FieldNode fieldClosure = getEnclosingClosureField(request.offsetNode, ctx.ast())
        if (fieldClosure) {
            String fieldName = fieldClosure.name
            // Domain class can be derived from visitor
            ClassNode domainClass = ctx.ast()?.getClassNodes(ctx.uri())?.find { it }
            if (domainClass == null) {
                domainClass = ctx.ast()?.getClassNodes()?.find { true }
            }

            if (domainClass) {
                if (fieldName == 'constraints') {
                    for (PropertyNode p : domainClass.properties) {
                        if (p.name != 'class' && p.name != 'metaClass' && !p.isStatic()) {
                            CompletionItem item = new CompletionItem(p.name)
                            item.kind = CompletionItemKind.Property
                            item.detail = "Domain property constraint"
                            item.insertText = (p.name + "(nullable: false)").toString()
                            completions.add(item)
                        }
                    }
                }
            }
        }
        return completions
    }

    private boolean isCriteriaMethodCall(MethodCallExpression methodCall) {
        if (methodCall == null) return false
        String name = methodCall.methodAsString
        if (name in ['createCriteria', 'withCriteria']) return true
        if (name in ['list', 'get', 'scroll'] && methodCall.objectExpression instanceof MethodCallExpression) {
            return isCriteriaMethodCall((MethodCallExpression) methodCall.objectExpression)
        }
        return false
    }

    private FieldNode getEnclosingClosureField(ASTNode node, ASTAccessor ast) {
        ASTNode current = node
        Set<ASTNode> visited = new HashSet<>()
        while (current != null && visited.add(current)) {
            if (current instanceof ClosureExpression) {
                ASTNode parent = ast.getParent(current)
                if (parent instanceof FieldNode) {
                    return (FieldNode) parent
                }
            }
            current = ast.getParent(current)
        }
        return null
    }

    private MethodCallExpression getEnclosingClosureMethodCall(ASTNode node, ASTAccessor ast) {
        ASTNode current = node
        Set<ASTNode> visited = new HashSet<>()
        while (current != null && visited.add(current)) {
            if (current instanceof ClosureExpression) {
                ASTNode parent = ast.getParent(current)
                if (parent instanceof MethodCallExpression) {
                    return (MethodCallExpression) parent
                }
                if (parent instanceof ArgumentListExpression) {
                    ASTNode grandParent = ast.getParent(parent)
                    if (grandParent instanceof MethodCallExpression) {
                        return (MethodCallExpression) grandParent
                    }
                }
            }
            current = ast.getParent(current)
        }
        return null
    }

    private ClassNode resolveDelegateType(MethodCallExpression methodCall, RequestContext ctx) {
        String methodName = methodCall.methodAsString
        if (methodName in ['with', 'tap']) {
            return getTypeOf(methodCall.objectExpression, ctx)
        }
        if (methodName in ['createCriteria', 'where', 'withCriteria']) {
            return getTypeOf(methodCall.objectExpression, ctx)
        }
        return null
    }
}
