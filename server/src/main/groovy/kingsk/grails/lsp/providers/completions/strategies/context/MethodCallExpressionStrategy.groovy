package kingsk.grails.lsp.providers.completions.strategies.context

import groovy.transform.CompileStatic
import kingsk.grails.lsp.context.RequestContext
import kingsk.grails.lsp.core.visitor.GrailsASTVisitor
import kingsk.grails.lsp.model.enums.CompletionTarget
import kingsk.grails.lsp.providers.completions.BaseCompletionStrategy
import kingsk.grails.lsp.providers.completions.CompletionRequest
import kingsk.grails.lsp.utils.ast.MemberExtractor
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.eclipse.lsp4j.CompletionItem

/**
 * Handles completions for method calls (obj.method(|))
 * Handles completions for method calls (obj.method(|)) and method call chain completions (obj.method().|)
 */
@CompileStatic
class MethodCallExpressionStrategy extends BaseCompletionStrategy {

	@Override
	int getPriority() { return 85 }

	@Override
	CompletionTarget target() { return CompletionTarget.BOTH }

	@Override
	boolean canHandle(CompletionRequest request, RequestContext ctx) { 
		request.offsetNode instanceof MethodCallExpression || request.parentNode instanceof MethodCallExpression
	}
	
	@Override
	List<CompletionItem> provideCompletions(CompletionRequest request, RequestContext ctx) {
		List<CompletionItem> completions = []
		MethodCallExpression methodCall = (request.offsetNode instanceof MethodCallExpression) ?
			(MethodCallExpression) request.offsetNode : (MethodCallExpression) request.parentNode

		// If methodCall has a return type (e.g. chained expression foo().|), provide return type members
		ClassNode returnType = getTypeOf(methodCall, ctx)
		if (returnType != null && returnType != ClassHelper.OBJECT_TYPE && returnType != ClassHelper.VOID_TYPE) {
			def members = MemberExtractor.collectMembers(returnType, false, returnType)
			members.methods.each { completions.add(kingsk.grails.lsp.utils.completion.CompletionUtil.buildCompletionItem(it)) }
			members.properties.each { completions.add(kingsk.grails.lsp.utils.completion.CompletionUtil.buildCompletionItem(it)) }
			ctx.grailsService()?.discoveryService?.getMethodsForType(returnType)?.each { String dgmMethod ->
				if (!members.methods.any { it.name == dgmMethod }) {
					CompletionItem item = new CompletionItem(dgmMethod)
					item.kind = org.eclipse.lsp4j.CompletionItemKind.Method
					item.detail = 'Groovy Default Method'
					completions.add(item)
				}
			}
			if (!completions.isEmpty()) return completions
		}

		Expression objectExpression = methodCall.objectExpression
		if (!objectExpression) return completions

		ClassNode objectType = getTypeOf(objectExpression, ctx)
		if (!objectType) return completions

		addMemberCompletions(objectExpression, ctx, completions)

		return completions
	}
}
