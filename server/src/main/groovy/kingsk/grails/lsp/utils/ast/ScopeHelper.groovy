package kingsk.grails.lsp.utils.ast

import groovy.transform.CompileStatic
import kingsk.grails.lsp.core.visitor.GrailsASTVisitor
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.Statement

/**
 * Collecting visible variables from method, block or closure scopes
 * Adding class fields, static imports, local variables
 * Managing common Grails objects and keyword completions
 *
 * Usage:
 * def items = ScopeCompletionHelper.collectScopeItems(request.offsetNode, request)
 * items.variables.each { ... }
 * items.fields.each { ... }
 * items.methods.each { ... }
 */
@CompileStatic
class ScopeHelper {

	/** Data class for scope completion items */
	static class ScopeItems {
		final List<Variable> variables = []
		final List<Parameter> parameters = []
		final MemberExtractor.CompletionItems members = new MemberExtractor.CompletionItems()
	}

	/** Enum for specifying what to collect */
	enum CollectionType {
		ALL, VARIABLES_ONLY, PARAMETERS_ONLY, MEMBERS_ONLY
	}

	/**
	 * Main entry point - collects variables visible from the given offset node
	 */
	static ScopeItems collectScopeItems(ASTNode offsetNode, GrailsASTVisitor visitor, CollectionType type = CollectionType.ALL) {
		if (!offsetNode) return new ScopeItems()

		ScopeItems items = new ScopeItems()

		// Start scope traversal from the appropriate node
		ASTNode startNode = offsetNode
		if (offsetNode instanceof MethodNode || offsetNode instanceof Statement) {
			collectFromScope(startNode, visitor, items, type)
		} else {
			// For other nodes, find the enclosing scope
			collectFromScope(startNode, visitor, items, type)
		}

		return items
	}

	// Convenience methods for specific item types

	static List<Variable> getVariables(ASTNode offsetNode, GrailsASTVisitor visitor) {
		return collectScopeItems(offsetNode, visitor, CollectionType.VARIABLES_ONLY).variables
	}

	static List<Parameter> getParameters(ASTNode offsetNode, GrailsASTVisitor visitor) {
		return collectScopeItems(offsetNode, visitor, CollectionType.PARAMETERS_ONLY).parameters
	}

	static MemberExtractor.CompletionItems getMembers(ASTNode offsetNode, GrailsASTVisitor visitor) {
		return collectScopeItems(offsetNode, visitor, CollectionType.MEMBERS_ONLY).members
	}

	// === PRIVATE IMPLEMENTATION ===

	private static void collectFromScope(ASTNode node, GrailsASTVisitor visitor, ScopeItems items, CollectionType type) {
		Set<String> seen = new HashSet<>()
		ASTNode current = node
		Set<ASTNode> visited = new HashSet<>()

		while (current != null && visited.add(current)) {
			if (current instanceof ClassNode) {
				ClassNode classNode = (ClassNode) current
				if (type == CollectionType.ALL || type == CollectionType.MEMBERS_ONLY) {
					collectClassMembers(classNode, items)
				}
			} else if (current instanceof MethodNode) {
				MethodNode methodNode = (MethodNode) current
				if (type == CollectionType.ALL || type == CollectionType.PARAMETERS_ONLY) {
					collectMethodParameters(methodNode, items, seen)
				}
				if (type == CollectionType.ALL || type == CollectionType.VARIABLES_ONLY) {
					collectFromVariableScope(methodNode.variableScope, items, seen)
				}
			} else if (current instanceof BlockStatement) {
				BlockStatement block = (BlockStatement) current
				if (type == CollectionType.ALL || type == CollectionType.VARIABLES_ONLY) {
					collectFromVariableScope(block.variableScope, items, seen)
				}
			} else if (current instanceof ClosureExpression) {
				ClosureExpression closure = (ClosureExpression) current
				if (type == CollectionType.ALL || type == CollectionType.PARAMETERS_ONLY) {
					collectClosureParameters(closure, items, seen)
				}
				if (type == CollectionType.ALL || type == CollectionType.VARIABLES_ONLY) {
					collectFromVariableScope(closure.variableScope, items, seen)
				}
			}
			current = visitor.getParent(current)
		}
	}

	private static void collectFromVariableScope(VariableScope variableScope, ScopeItems items, Set<String> existingNames) {
		if (!variableScope?.declaredVariables) return

		variableScope.declaredVariables.values().each { Variable variable ->
			String variableName = variable.name
			if (!existingNames.contains(variableName)) {
				existingNames.add(variableName)
				items.variables << variable
			}
		}
	}

	private static void collectMethodParameters(MethodNode method, ScopeItems items, Set<String> existingNames) {
		Parameter[] params = method.parameters
		if (params) {
			for (Parameter param : params) {
				if (!existingNames.contains(param.name)) {
					existingNames.add(param.name)
					items.parameters << param
				}
			}
		}
	}

	private static void collectClosureParameters(ClosureExpression closure, ScopeItems items, Set<String> existingNames) {
		Parameter[] params = closure.parameters
		if (params) {
			for (Parameter param : params) {
				if (!existingNames.contains(param.name)) {
					existingNames.add(param.name)
					items.parameters << param
				}
			}
		} else if (!existingNames.contains('it')) {
			// Add implicit 'it' parameter if no explicit parameters
			existingNames.add('it')
			items.parameters << new Parameter(ClassHelper.OBJECT_TYPE, 'it')
		}
	}

	private static void collectClassMembers(ClassNode classNode, ScopeItems items) {
		// Use MemberExtractor to get class members (non-static for instance access)
		def members = MemberExtractor.collectMembers(classNode, false, classNode)
		items.members.fields.addAll(members.fields)
		items.members.methods.addAll(members.methods)
		items.members.properties.addAll(members.properties)
	}
}
