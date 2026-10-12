package kingsk.grails.lsp.utils.ast

import groovy.util.logging.Slf4j
import kingsk.grails.lsp.GrailsService
import kingsk.grails.lsp.core.visitor.GrailsASTVisitor
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.utils.position.RangeHelper
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

@Slf4j
class GrailsASTHelper {

    //==== PUBLIC API ====

    /**
     * Supports: CodeCompletion, InlayHints, Hover, Documentation, ParameterInfo
     *
     * Determines the type of a given AST node.
     *
     * @param node The AST node whose type is to be resolved.
     * @param visitor The AST visitor providing context.
     * @return ClassNode representing the resolved type, or null if not resolvable.
     */
    static ClassNode getTypeOfNode(ASTNode node, GrailsASTVisitor visitor) {
        ClassNode rawType = resolveTypeOfNodeInternal(node, visitor)
        if (rawType != null && visitor != null && rawType != ClassHelper.OBJECT_TYPE && rawType != ClassHelper.DYNAMIC_TYPE && rawType != ClassHelper.VOID_TYPE) {
            ClassNode resolved = tryToResolveOriginalClassNode(rawType, false, visitor)
            if (resolved != null) return resolved
        }
        return rawType
    }

    private static ClassNode resolveTypeOfNodeInternal(ASTNode node, GrailsASTVisitor visitor) {
        switch (node) {
            case BinaryExpression: return resolveBinaryExpressionType(node, visitor)
            case ClassExpression: return node.type // expression: SomeClass.someProp
            case ConstructorCallExpression: return node.type
            case MethodCallExpression:
                MethodNode methodNode = getMethodFromCallExpression(node, visitor)
                if (methodNode != null && methodNode.returnType != null && methodNode.returnType != ClassHelper.OBJECT_TYPE) {
                    return methodNode.returnType
                }
                String mName = node.methodAsString
                if (mName != null) {
                    ClassNode target = getTypeOfNode(node.objectExpression, visitor)
                    if (target != null) {
                        if (mName.startsWith('findAllBy') || mName in ['findAll', 'list', 'getAll']) {
                            return ClassHelper.make(List.class)
                        }
                        if (mName.startsWith('findBy') || mName in ['get', 'load', 'read', 'findWhere']) {
                            return target
                        }
                        if (mName.startsWith('countBy') || mName in ['count']) {
                            return ClassHelper.Integer_TYPE
                        }
                    }
                }
                return methodNode?.returnType ?: node.type
            case PropertyExpression: return resolvePropertyExpressionType(node, visitor)
            case Variable: return resolveVariableType(node as Variable, visitor)
            case Expression: return node.type
            default: null
        }
    }

    /**
     * Supports: Definition, References, Navigate to enclosing class/method
     *
     * Finds the definition node for a given AST node.
     *
     * @param node The node to resolve.
     * @param strict If true, only returns exact matches; if false, may return best-effort matches.
     * @param visitor The AST visitor providing context.
     * @return The definition ASTNode, or null if not found.
     *                Used for "Go to Definition" and hover features.
     */
    static ASTNode getDefinition(ASTNode node, boolean strict, GrailsASTVisitor visitor) {
        if (!node) return null
        // if node is an ExpressionStatement then update the node
        node = (node instanceof ExpressionStatement) ? node.expression : node

        switch (node) {
            case ImportNode: return tryToResolveOriginalClassNode(node.type, strict, visitor)
            case ClassExpression: return tryToResolveOriginalClassNode(node.type, strict, visitor)
            case ClassNode: return tryToResolveOriginalClassNode(node, strict, visitor)
            case ConstructorCallExpression: return getMethodFromCallExpression(node, visitor)
            case ConstructorNode: return node.declaringClass
            case DeclarationExpression: return (!node.multipleAssignmentDeclaration) ? tryToResolveOriginalClassNode(node.variableExpression.originType, strict, visitor) : null
            case ConstantExpression: return resolveFromConstantExpression(node, visitor)
            case MethodCallExpression: return getMethodFromCallExpression(node, visitor)
            case PropertyExpression: return getPropertyFromExpression(node, visitor) ?: getFieldFromExpression(node, visitor)
            case VariableExpression:
                if (node.accessedVariable instanceof ASTNode) {
                    node.accessedVariable as ASTNode
                }
                // If no accessed variable, try to resolve the type
                return tryToResolveOriginalClassNode(node.originType, strict, visitor)
            case PackageNode: return node
            case MethodNode: return node
            case Variable: return node
            default: return null
        }
    }

    /**
     * Supports: Definition, References, Navigate to enclosing class/method
     *
     * Finds the type definition node for a given AST node.
     *
     * @param node The node whose type definition is to be found.
     * @param visitor The AST visitor providing context.
     * @return The type definition ASTNode, or null if not found.
     *                Used for "Go to Type Definition".
     */
    static ASTNode getTypeDefinition(ASTNode node, GrailsASTVisitor visitor) {
        ASTNode definitionNode = getDefinition(node, false, visitor)
        if (!definitionNode) return null

        if (definitionNode instanceof MethodNode) return tryToResolveOriginalClassNode(definitionNode.returnType, false, visitor)
        if (definitionNode instanceof Variable) return tryToResolveOriginalClassNode(definitionNode.originType, false, visitor)

        if (definitionNode instanceof ConstructorNode) {
            // For constructor, return the declaring class
            return definitionNode.declaringClass
        }
        if (definitionNode instanceof ClassNode) {
            // If we already have a ClassNode, return it directly
            return definitionNode
        }

        return null
    }

    /**
     * Supports: Definition, References, Navigate to enclosing class/method
     *
     * Finds the nearest enclosing node of a specified type.
     *
     * @param offsetNode The starting node.
     * @param nodeType The type of node to search for.
     * @param visitor The AST visitor providing context.
     * @return The enclosing node of the specified type, or null if not found.
     *                   Used for context-aware completions and navigation.
     */
    static ASTNode getEnclosingNodeOfType(ASTNode offsetNode, Class<ASTNode> nodeType, GrailsASTVisitor visitor) {
        def current = offsetNode
        while (current) {
            if (nodeType.isInstance(current)) return current
            ASTNode newParent = visitor.getParent(current)
            if (newParent && newParent == current) break
            current = newParent
        }
        return null
    }

    /**
     * Supports: Definition, References, DocumentSymbol, WorkspaceSymbol, Navigate to enclosing class/method
     *
     * Finds the enclosing class node for a given AST node.
     *
     * @param offsetNode The starting node.
     * @param visitor The AST visitor providing context.
     * @return The enclosing ClassNode, or null if not found.
     *                   Used for symbol navigation and context resolution.
     */
    static ClassNode getEnclosingClassNode(ASTNode offsetNode, GrailsASTVisitor visitor) {
        return getEnclosingNodeOfType(offsetNode, ClassNode.class, visitor) as ClassNode
    }

    static ModuleNode getEnclosingModuleNode(ASTNode offsetNode, GrailsASTVisitor visitor) {
        return getEnclosingNodeOfType(offsetNode, ModuleNode.class, visitor) as ModuleNode
    }

    static MethodNode getEnclosingMethodNode(ASTNode offsetNode, GrailsASTVisitor visitor) {
        return getEnclosingNodeOfType(offsetNode, MethodNode.class, visitor) as MethodNode
    }

    /**
     * Supports: Rename, References
     *
     * Finds all references to the definition of a given node.
     *
     * @param node The node whose references are to be found.
     * @param visitor The AST visitor providing context.
     * @param currentPosition The current cursor position (may affect disambiguation).
     * @return List<ASTNode> of reference nodes, or null if none found.
     *                        Used for "Find References" and rename refactoring.
     */
    static List<ASTNode> getReferences(ASTNode node, GrailsASTVisitor visitor, Position currentPosition) {
        if (!node || node.lineNumber == -1 || node.columnNumber == -1) return null

        def definitionNode = getDefinition(node, true, visitor)
        if (!definitionNode) {
            log.info("getReferences - definitionNode = null")
            return null
        }

        if ((definitionNode instanceof PropertyNode) && currentPosition) {
            PropertyNode propNode = (PropertyNode) definitionNode
            ClassNode variableType = tryToResolveOriginalClassNode(propNode.originType, true, visitor)
            FieldNode variableField = propNode.field
            Range typeRange = !variableType ? null : ASTUtils.astNodeToRange(variableType)
            Range fieldRange = !variableField ? null : ASTUtils.astNodeToRange(variableField)

            // Give preference to variable where possible
            if (fieldRange && RangeHelper.contains(fieldRange, currentPosition)) {
                definitionNode = variableField
            } else if (typeRange && RangeHelper.contains(typeRange, currentPosition)) {
                definitionNode = variableField ?: definitionNode
            }
        }

        def references = visitor.getNodes().findAll { otherNode ->
            if (otherNode.lineNumber == -1 || otherNode.columnNumber == -1) return false

            def otherDefinition = getDefinition(otherNode, false, visitor)
            return otherDefinition && isAnnotatedNodeEqual(definitionNode, otherDefinition, visitor)
        }

        return references
    }

    /**
     * Supports: SignatureHelp, InlayHints
     *
     * Finds the best matching method node for a method call expression.
     *
     * @param node The method call expression.
     * @param visitor The AST visitor providing context.
     * @param argIndex The argument index for overload resolution (optional).
     * @return The best matching MethodNode, or null if not found.
     *                 Used for signature help and method resolution.
     */
    static MethodNode getMethodFromCallExpression(MethodCall node, GrailsASTVisitor visitor, int argIndex = -1) {
        def overloads = getMethodOverloadsFromCallExpression(node, visitor)
        def args = getArgumentListExpression(node)
        return GrailsASTHelperUtils.findBestMatchingMethodNode(overloads, args, argIndex)
    }

    static ArgumentListExpression getArgumentListExpression(MethodCall node) {
        if (!node?.arguments) return null
        if (node == null || node.arguments == null) return null
        def args = node.arguments
        if (!(args instanceof ArgumentListExpression)) return null
        return args
        return (ArgumentListExpression) args
    }

    /**
     * Supports: SignatureHelp, InlayHints
     *
     * Retrieves all method overloads for a given method or constructor call expression.
     *
     * @param node The method or constructor call expression.
     * @param visitor The AST visitor providing context.
     * @return List<MethodNode> of overloads, or an empty list if none found.
     *                Used for signature help and completion.
     */
    static List<MethodNode> getMethodOverloadsFromCallExpression(MethodCall node, GrailsASTVisitor visitor) {
        if (node instanceof MethodCallExpression) {
            return getTypeOfNode(node.objectExpression, visitor)?.getMethods(node.method.text)
            ClassNode targetType = null
            if (node.implicitThis || (node.objectExpression instanceof VariableExpression && ((VariableExpression) node.objectExpression).thisExpression)) {
                targetType = getEnclosingClassNode(node, visitor)
            } else if (node.objectExpression instanceof VariableExpression && ((VariableExpression) node.objectExpression).superExpression) {
                targetType = getEnclosingClassNode(node, visitor)?.superClass
            } else {
                targetType = getTypeOfNode(node.objectExpression, visitor)
            }
            return targetType?.getMethods(node.method.text) ?: []
        } else if (node instanceof ConstructorCallExpression) {
            return node.type?.declaredConstructors?.toList() as List<MethodNode>
        }
        return []
    }

    /**
     * Supports: CodeCompletions
     *
     * Finds the appropriate range in the source file to add a new import statement.
     *
     * @param offsetNode The node indicating the insertion point.
     * @param visitor The AST visitor providing context.
     * @return Range indicating where to insert the import.
     *                   Used for auto-import code actions.
     */
    static Range findAddImportRange(ASTNode offsetNode, GrailsASTVisitor visitor) {
        ModuleNode moduleNode = getEnclosingNodeOfType(offsetNode, ModuleNode.class as Class<ASTNode>, visitor) as ModuleNode
        if (!moduleNode) return RangeHelper.zeroRange()

        ASTNode afterNode = moduleNode.imports ? moduleNode.imports[-1] : moduleNode.package
        if (!afterNode) return RangeHelper.zeroRange()

        Range nodeRange = ASTUtils.astNodeToRange(afterNode)
        if (!nodeRange) return RangeHelper.zeroRange()

        Position position = new Position(nodeRange.end.line + 1, 0)
        return new Range(position, position)
    }

    //==== PRIVATE METHODS ====

    private static ASTNode resolveFromConstantExpression(ConstantExpression node, GrailsASTVisitor visitor) {
        ASTNode parent = visitor.getParent(node)
        if (parent instanceof MethodCallExpression) return getMethodFromCallExpression(parent, visitor)
        if (parent instanceof PropertyExpression) {
            return getPropertyFromExpression(parent, visitor) ?: getFieldFromExpression(parent, visitor)
        }
        return null
    }

    private static ClassNode resolveBinaryExpressionType(BinaryExpression node, GrailsASTVisitor visitor) {
        def left = node.leftExpression
        ClassNode leftType = getTypeOfNode(left, visitor)
        log.debug("[DEBUG_ARRAY] resolveBinaryExpressionType: left=${left.text}, leftType=${leftType?.name}, isArray=${leftType?.isArray()}, operation=${node.operation.text}")
        return (node.operation.text == '[' && leftType?.isArray()) ? leftType.componentType : null
    }

    private static ClassNode resolvePropertyExpressionType(PropertyExpression node, GrailsASTVisitor visitor) {
        PropertyNode propertyNode = getPropertyFromExpression(node, visitor)
        if (propertyNode) return getTypeOfNode(propertyNode, visitor)
        FieldNode fieldNode = getFieldFromExpression(node, visitor)
        if (fieldNode) return getTypeOfNode(fieldNode, visitor)

        // Grails domain conventions: hasMany, hasOne, belongsTo, id, version
        ClassNode objType = getTypeOfNode(node.objectExpression, visitor)
        if (objType != null) {
            String propName = node.propertyAsString
            if (propName == 'id' || propName == 'version') {
                return ClassHelper.make(Long.class)
            }
            if (propName == 'errors') {
                return ClassHelper.make('org.springframework.validation.Errors')
            }
            Expression hasManyExpr = findStaticFieldInitialExpression(objType, 'hasMany')
            if (hasManyExpr instanceof MapExpression) {
                for (MapEntryExpression entry : ((MapExpression) hasManyExpr).mapEntryExpressions) {
                    if (entry.keyExpression.text == propName) {
                        return ClassHelper.make(Set.class)
                    }
                }
            }
            Expression hasOneExpr = findStaticFieldInitialExpression(objType, 'hasOne')
            if (hasOneExpr instanceof MapExpression) {
                for (MapEntryExpression entry : ((MapExpression) hasOneExpr).mapEntryExpressions) {
                    if (entry.keyExpression.text == propName && entry.valueExpression instanceof ClassExpression) {
                        return ((ClassExpression) entry.valueExpression).type
                    }
                }
            }
            Expression belongsToExpr = findStaticFieldInitialExpression(objType, 'belongsTo')
            if (belongsToExpr instanceof MapExpression) {
                for (MapEntryExpression entry : ((MapExpression) belongsToExpr).mapEntryExpressions) {
                    if (entry.keyExpression.text == propName && entry.valueExpression instanceof ClassExpression) {
                        return ((ClassExpression) entry.valueExpression).type
                    }
                }
            }
        }

        return node.type
    }

    private static Expression findStaticFieldInitialExpression(ClassNode classNode, String fieldName) {
        if (classNode == null || !fieldName) return null
        FieldNode field = classNode.getField(fieldName)
        if (field?.hasInitialExpression()) return field.initialExpression
        PropertyNode prop = classNode.getProperty(fieldName)
        if (prop?.hasInitialExpression()) return prop.initialExpression
        if (field?.initialExpression != null) return field.initialExpression
        if (prop?.initialExpression != null) return prop.initialExpression

        for (MethodNode method : classNode.methods) {
            if (method.name == '<clinit>' && method.code instanceof BlockStatement) {
                for (Statement stmt : ((BlockStatement) method.code).statements) {
                    if (stmt instanceof ExpressionStatement) {
                        Expression expr = ((ExpressionStatement) stmt).expression
                        if (expr instanceof BinaryExpression && ((BinaryExpression) expr).operation.text == '=') {
                            BinaryExpression bin = (BinaryExpression) expr
                            if (bin.leftExpression.text == fieldName || bin.leftExpression.text.endsWith('.' + fieldName)) {
                                return bin.rightExpression
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    static ClassNode resolveVariableType(Variable variable, GrailsASTVisitor visitor) {
        if (variable instanceof VariableExpression) {
            VariableExpression varExpr = (VariableExpression) variable
            if (varExpr.thisExpression) {
                return getEnclosingClassNode(varExpr, visitor)
            }
            if (varExpr.superExpression) {
                return getEnclosingClassNode(varExpr, visitor)?.superClass
            }
            if (varExpr.accessedVariable != null && varExpr.accessedVariable != varExpr) {
                return resolveVariableType(varExpr.accessedVariable, visitor)
            }
            // If accessedVariable is null, look up in enclosing method
            MethodNode enclosingMethod = getEnclosingMethodNode(varExpr, visitor)
            if (enclosingMethod != null) {
                Parameter param = enclosingMethod.parameters?.find { it.name == varExpr.name }
                if (param != null) return param.type ?: param.originType
            }
            // Check enclosing class fields and properties
            ClassNode enclosingClass = getEnclosingClassNode(varExpr, visitor)
            if (enclosingClass != null) {
                PropertyNode prop = enclosingClass.getProperty(varExpr.name)
                if (prop != null) return prop.type ?: prop.originType
                FieldNode field = enclosingClass.getField(varExpr.name)
                if (field != null) return field.type ?: field.originType
            }
            // Look for declaration in visitor nodes for this file
            String uri = visitor.getURI(varExpr)
            def fileNodes = visitor.getNodes(uri)
            if (fileNodes) {
                for (ASTNode candidate : fileNodes) {
                    if (candidate instanceof DeclarationExpression) {
                        DeclarationExpression decl = (DeclarationExpression) candidate
                        if (decl.variableExpression?.name == varExpr.name) {
                            ClassNode declaredType = decl.variableExpression.originType ?: decl.variableExpression.type
                            if (declaredType != null && declaredType != ClassHelper.OBJECT_TYPE && declaredType != ClassHelper.DYNAMIC_TYPE) {
                                return declaredType
                            }
                            if (decl.rightExpression != null) {
                                ClassNode inferred = getTypeOfNode(decl.rightExpression, visitor)
                                if (inferred != null && inferred != ClassHelper.OBJECT_TYPE) {
                                    return inferred
                                }
                            }
                            return declaredType
                        }
                    }
                }
            }
        }
        if (variable.dynamicTyped) {
            def definition = getDefinition(variable as ASTNode, false, visitor)
            if (definition instanceof Variable) {
                if (definition.hasInitialExpression()) {
                    return getTypeOfNode(definition.initialExpression, visitor)
                }
                ASTNode parentNode = visitor.getParent(definition)
                if (parentNode instanceof DeclarationExpression) {
                    return getTypeOfNode(parentNode.rightExpression, visitor)
                }
            }
            def allNodes = visitor.getNodes(null)
            if (allNodes) {
                for (ASTNode candidate : allNodes) {
                    if (candidate instanceof DeclarationExpression) {
                        DeclarationExpression decl = (DeclarationExpression) candidate
                        if (decl.variableExpression?.name == variable.name && decl.rightExpression != null) {
                            ClassNode inferred = getTypeOfNode(decl.rightExpression, visitor)
                            if (inferred != null && inferred != ClassHelper.OBJECT_TYPE && inferred != ClassHelper.DYNAMIC_TYPE) {
                                return inferred
                            }
                        }
                    }
                }
            }
        }
        if (variable instanceof VariableExpression) {
            VariableExpression varExpr = (VariableExpression) variable
            ClassNode matchedClass = visitor?.classNodes?.find {
                it.name == varExpr.name || it.nameWithoutPackage == varExpr.name
            }
            if (matchedClass != null) {
                return matchedClass
            }
        }
        return variable.originType ?: variable.type
    }

    private static ClassNode tryToResolveOriginalClassNode(ClassNode node, boolean strict, GrailsASTVisitor visitor) {
        if (!node) return null

        // Step 1: Check current AST first (fast path)
        def originalNode = visitor?.classNodes?.find {
            it == node || it.name == node.name || (!node.name.contains('.') && it.nameWithoutPackage == node.name)
        }
        if (originalNode) {
            log.debug "Original node found in current AST: ${originalNode.nameWithoutPackage}"
            return originalNode
        }

        // Step 2: Try cross-file resolution for Grails artifacts
        if (!strict && visitor?.service != null) {
            ClassNode crossFileNode = tryToResolveFromRelatedFiles(node.name, visitor.service)
            if (crossFileNode) {
                log.debug "Original node found in related files: ${crossFileNode.nameWithoutPackage}"
                return crossFileNode
            }
        }

        log.debug "Original node not found ${strict ? "(strict mode)" : ", fallback"}, returning ${node?.nameWithoutPackage}"
        return strict ? null : node
    }

    /**
     * Try to resolve ClassNode by visiting related Grails files (services, domains, controllers)
     * This enables cross-file definition resolution without state conflicts
     */
    private static ClassNode tryToResolveFromRelatedFiles(String fqcnKey, GrailsService service) {
        if (!fqcnKey) return null
        // Get the service instance to access file tracker and compiler
        if (!service) return null

        // Find potential files that might contain this class
        TextFile current = service.fileTracker.FQCNIndex[fqcnKey]
        // Make sure file does not contain itself
        Set<TextFile> dependencies = service.fileTracker.getFileAndItsDependencies(current)
            ?.findAll { it != current }

        // Visit candidate files and check for the class
        for (TextFile dependentFile : dependencies) {
            ClassNode foundNode = visitFileAndFindClass(dependentFile, fqcnKey, service)
            if (foundNode) {
                return foundNode
            }
        }

        return null
    }

    /**
     * Visit a file and search for the target class
     */
    private static ClassNode visitFileAndFindClass(TextFile candidateFile, String targetFile, GrailsService service) {
        if (!candidateFile) return null
        if (!targetFile) return null

        // Check existing AST first
        Set<ClassNode> existingClasses = service.visitor.getClassNodes(candidateFile.uri)
        if (existingClasses) {
            ClassNode found = existingClasses.find { it.name == targetFile }
            if (found) return found
        }

        // Visit the AST for the file
        service.visitAST(candidateFile)

        // Check again after visiting
        Set<ClassNode> newClasses = service.visitor.getClassNodes(candidateFile.uri)
        return newClasses?.find { it.name == targetFile }
    }

    // Extracts single property/field from obj.property expressions
    static PropertyNode getPropertyFromExpression(PropertyExpression node, GrailsASTVisitor visitor) {
        return getTypeOfNode(node.objectExpression, visitor)?.getProperty(node.property.text)
    }

    static FieldNode getFieldFromExpression(PropertyExpression node, GrailsASTVisitor visitor) {
        return getTypeOfNode(node.objectExpression, visitor)?.getField(node.property.text)
    }

    private static boolean isAnnotatedNodeEqual(ASTNode declaringNode, ASTNode otherNode, GrailsASTVisitor visitor) {
        if (declaringNode == otherNode) return true

        if ((declaringNode instanceof MethodNode) && (otherNode instanceof MethodNode)) {
            return GrailsASTHelperUtils.compareMethodNodeNameLineAndColumn(otherNode, declaringNode)
        } else if (declaringNode instanceof FieldNode) {
            if (otherNode instanceof FieldNode) {
                return GrailsASTHelperUtils.compareFieldNodeNameLineAndColumn(otherNode, declaringNode)
            } else if (otherNode instanceof PropertyNode) {
                return GrailsASTHelperUtils.compareFieldNodeNameLineAndColumn(otherNode?.field, declaringNode)
            } else if (otherNode instanceof PropertyExpression) {
                def fieldNode = getFieldFromExpression(otherNode, visitor)
                return GrailsASTHelperUtils.compareFieldNodeNameLineAndColumn(declaringNode, fieldNode)
            }
        }

        return false
    }
}