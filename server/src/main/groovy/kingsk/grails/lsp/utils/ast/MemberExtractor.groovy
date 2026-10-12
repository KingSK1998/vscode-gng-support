package kingsk.grails.lsp.utils.ast

import groovy.transform.CompileStatic
import kingsk.grails.lsp.core.visitor.GrailsASTVisitor
import kingsk.grails.lsp.utils.grails.GrailsUtils
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.PropertyExpression

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import groovy.util.logging.Slf4j

/**
 * Extracts class members from Groovy AST expressions for LSP completion support.
 * Handles inheritance hierarchy traversal with duplicate elimination.
 */
@Slf4j
@CompileStatic
class MemberExtractor {

    /** Data class for completion items */
    static class CompletionItems {
        final List<PropertyNode> properties = []
        final List<FieldNode> fields = []
        final List<MethodNode> methods = []
    }

    /** Enum for specifying what to collect */
    enum CollectionType {
        ALL, FIELDS_ONLY, METHODS_ONLY, PROPERTIES_ONLY
    }

    /**
     * Main entry point - collects members from expression type
     */
    static CompletionItems collectMembers(Expression expression, GrailsASTVisitor visitor, CollectionType type = CollectionType.ALL) {
        ClassNode expressionType = GrailsASTHelper.getTypeOfNode(expression, visitor)
        if (!expressionType) return new CompletionItems()

        boolean statics = expression instanceof ClassExpression
        ClassNode currentClass = GrailsASTHelper.getEnclosingClassNode(expression, visitor)

        return collectMembers(expressionType, statics, currentClass, type)
    }

    /**
     * Collect members from a class node directly
     */
    static CompletionItems collectMembers(ClassNode classNode, boolean statics, ClassNode currentClass = null, CollectionType type = CollectionType.ALL) {
        if (!classNode) return new CompletionItems()

        CompletionItems items = new CompletionItems()
        Set<ClassNode> visited = [] as Set

        collectFromHierarchy(classNode, statics, currentClass ?: classNode, items, visited, type)

        return items
    }

    // Convenience methods for specific member types

    static List<FieldNode> getFields(Expression expression, GrailsASTVisitor visitor) {
        return collectMembers(expression, visitor, CollectionType.FIELDS_ONLY).fields
    }

    static List<MethodNode> getMethods(Expression expression, GrailsASTVisitor visitor) {
        return collectMembers(expression, visitor, CollectionType.METHODS_ONLY).methods
    }

    static List<PropertyNode> getProperties(Expression expression, GrailsASTVisitor visitor) {
        return collectMembers(expression, visitor, CollectionType.PROPERTIES_ONLY).properties
    }

    // Single member lookups

    static PropertyNode getProperty(PropertyExpression node, GrailsASTVisitor visitor) {
        return GrailsASTHelper.getTypeOfNode(node.objectExpression, visitor)?.getProperty(node.property.text)
    }

    static FieldNode getField(PropertyExpression node, GrailsASTVisitor visitor) {
        return GrailsASTHelper.getTypeOfNode(node.objectExpression, visitor)?.getField(node.property.text)
    }

    // === PRIVATE IMPLEMENTATION ===

    private static void collectFromHierarchy(ClassNode classNode, boolean statics, ClassNode currentClass,
                                             CompletionItems items, Set<ClassNode> visited, CollectionType type) {
        if (!visited.add(classNode)) return

        // Collect based on type filter
        switch (type) {
            case CollectionType.ALL:
                collectFields(classNode, statics, currentClass, items)
                collectMethods(classNode, statics, currentClass, items)
                collectProperties(classNode, statics, currentClass, items)
                break
            case CollectionType.FIELDS_ONLY:
                collectFields(classNode, statics, currentClass, items)
                break
            case CollectionType.METHODS_ONLY:
                collectMethods(classNode, statics, currentClass, items)
                break
            case CollectionType.PROPERTIES_ONLY:
                collectProperties(classNode, statics, currentClass, items)
                break
        }

        // Handle special case for java.lang.Object or resolved/bytecode classes
        Class<?> resolvedClass = null
        if (classNode.isResolved()) {
            try {
                resolvedClass = classNode.typeClass
            } catch (Throwable ignored) {
            }
        }
        if (resolvedClass == null && classNode.name) {
            try {
                ClassNode cn = ClassHelper.make(classNode.name)
                if (cn.isResolved()) {
                    resolvedClass = cn.typeClass
                }
            } catch (Throwable ignored) {
            }
        }
        if (resolvedClass == null && classNode.name) {
            try {
                resolvedClass = Class.forName(classNode.name)
            } catch (Throwable ignored) {
            }
            if (resolvedClass == null && !classNode.name.contains('.')) {
                try {
                    resolvedClass = Class.forName("java.lang." + classNode.name)
                } catch (Throwable ignored) {
                }
            }
        }

        if (classNode.name == GrailsUtils.TYPE_OBJECT && classNode.getClass() != null) {
            collectFromJavaLangClass(statics, items, type)
        } else if (resolvedClass != null && (type == CollectionType.ALL || type == CollectionType.METHODS_ONLY)) {
            try {
                for (Method method : resolvedClass.getMethods()) {
                    if (Modifier.isStatic(method.modifiers) == statics && Modifier.isPublic(method.modifiers)) {
                        MethodNode mn = convertMethodToMethodNode(method)
                        if (!items.methods.any { it.name == mn.name && it.parameters?.length == mn.parameters?.length }) {
                            items.methods << mn
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // Traverse hierarchy
        traverseHierarchy(classNode, statics, currentClass, items, visited, type)
    }

    private static void collectFields(ClassNode classNode, boolean statics, ClassNode currentClass, CompletionItems items) {
        classNode.fields?.findAll {
            it.static == statics &&
                isVisibleField(it, currentClass) &&
                shouldIncludeField(it)  // Add filtering here
        }?.each {
            items.fields << it
        }
    }

    private static void collectMethods(ClassNode classNode, boolean statics, ClassNode currentClass, CompletionItems items) {
        classNode.methods?.findAll {
            it.static == statics &&
                isVisibleMethod(it, currentClass) &&
                shouldIncludeMethod(it)  // Add filtering here
        }?.each {
            items.methods << it
        }
    }

    private static void collectProperties(ClassNode classNode, boolean statics, ClassNode currentClass, CompletionItems items) {
        classNode.properties?.findAll { it.static == statics }?.each {
            items.properties << it
        }
    }

    private static void traverseHierarchy(ClassNode classNode, boolean statics, ClassNode currentClass,
                                          CompletionItems items, Set<ClassNode> visited, CollectionType type) {
        // Process interfaces
        classNode.interfaces?.each { iface ->
            collectFromHierarchy(iface, statics, currentClass, items, visited, type)
        }

        // Process superclass (skip for interfaces when looking for instance members)
        if (!classNode.interface || statics) {
            try {
                classNode.superClass?.with { superClass ->
                    collectFromHierarchy(superClass, statics, currentClass, items, visited, type)
                }
            } catch (NoClassDefFoundError ignored) {
                log.warn("Error: No class definition found for superclass: ${classNode.name}")
            }
        }
    }

    private static void collectFromJavaLangClass(boolean statics, CompletionItems items, CollectionType type) {
        Class<?> javaLangClass = Class.class
        if (!javaLangClass) return

        if (type == CollectionType.ALL || type == CollectionType.METHODS_ONLY) {
            javaLangClass.getMethods()
                .findAll { Modifier.isStatic(it.modifiers) == statics }
                .each { method -> items.methods << convertMethodToMethodNode(method) }
        }

        if (type == CollectionType.ALL || type == CollectionType.FIELDS_ONLY) {
            javaLangClass.getFields()
                .findAll { Modifier.isStatic(it.modifiers) == statics }
                .each { field -> items.fields << FieldNode.newStatic(javaLangClass, field.name) }
        }
    }

    // Visibility checking methods (from your original code)

    /**
     * Determines if a node is visible in the current completion context.
     */
    private static boolean isVisibleMethod(MethodNode node, ClassNode currentClass) {
        if (!node) return false
        if (node.public) return true
        if (node.protected) return isSubclassOrSamePackage(node.declaringClass, currentClass)
        if (node.private) return inSameClass(node.declaringClass, currentClass)
        // if (isPackagePrivate(node.getModifiers())) return inSamePackage(node.declaringClass, currentClass)
        return false
    }

    private static boolean isVisibleField(FieldNode node, ClassNode currentClass) {
        if (!node) return false
        if (node.private) return inSameClass(node.declaringClass, currentClass)
        if (node.protected) return isSubclassOrSamePackage(node.declaringClass, currentClass)
        if (node.public) return true
        // if (isPackagePrivate(node.modifiers)) return inSamePackage(node.declaringClass, currentClass)
        return false
    }

    //	private static boolean isPackagePrivate(int modifiers) {
    //		return !(modifiers & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE))
    //	}

    private static boolean isSubclassOrSamePackage(ClassNode declaring, ClassNode current) {
        return current.isDerivedFrom(declaring) || inSamePackage(declaring, current)
    }

    private static boolean inSamePackage(ClassNode a, ClassNode b) {
        return a.package == b.package
    }

    private static boolean inSameClass(ClassNode a, ClassNode b) {
        return a.name == b.name
    }

    private static MethodNode convertMethodToMethodNode(Method method) {
        Parameter[] parameters = method.parameters.collect { param ->
            new Parameter(ClassHelper.make(param.type), param.name)
        }

        return new MethodNode(
            method.name,
            method.modifiers,
            ClassHelper.make(method.returnType),
            parameters,
            ClassNode.EMPTY_ARRAY, // exceptions
            null // no code body
        )
    }

    // Add filtering methods
    private static boolean shouldIncludeMethod(MethodNode method) {
        String name = method.name

        // Filter out Grails trait methods
        if (name.contains('trait$super$')) return false
        if (name.contains('__')) return false
        if (name.endsWith('$get') || name.endsWith('$set')) return false
        if (name.startsWith('grails_')) return false
        if (name.contains('$')) return false

        return true
    }

    private static boolean shouldIncludeField(FieldNode field) {
        String name = field.name

        // Filter out Grails internal fields
        if (name.startsWith('grails_')) return false
        if (name.contains('__')) return false

        // Include user-facing fields
        return true
    }
}
