package kingsk.grails.lsp.index

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.CodeVisitorSupport
import kingsk.grails.lsp.utils.ast.ASTUtils
import org.eclipse.lsp4j.SymbolKind
import kingsk.grails.lsp.model.enums.GrailsArtifactType

@CompileStatic
class IndexBuilder {

    static List<SymbolInfo> buildSymbols(String uri, List<ClassNode> classNodes) {
        List<SymbolInfo> symbols = []
        Map<String, Integer> overloadCounts = [:]

        for (ClassNode clazz : classNodes) {
            String containerDescriptor = clazz.name
            
            // Class symbol
            symbols.add(new SymbolInfo(
                containerDescriptor,
                null,
                clazz.nameWithoutPackage,
                SymbolKind.Class,
                ASTUtils.astNodeToRange(clazz),
                ASTUtils.astNodeToRange(clazz),
                clazz.name,
                clazz.superClass?.name ?: "java.lang.Object",
                determineArtifactType(clazz),
                clazz.modifiers,
                uri,
                "Class"
            ))

            // Fields
            for (FieldNode field : clazz.fields) {
                if (field.isSynthetic()) continue
                String desc = buildDescriptor(clazz, field)
                symbols.add(new SymbolInfo(
                    desc,
                    containerDescriptor,
                    field.name,
                    SymbolKind.Field,
                    ASTUtils.astNodeToRange(field),
                    ASTUtils.astNodeToRange(field),
                    "${field.type.nameWithoutPackage} ${field.name}",
                    field.type.name,
                    null,
                    field.modifiers,
                    uri,
                    field.type.name
                ))
            }

            // Properties
            for (PropertyNode prop : clazz.properties) {
                if (prop.isSynthetic()) continue
                String desc = buildDescriptor(clazz, prop)
                symbols.add(new SymbolInfo(
                    desc,
                    containerDescriptor,
                    prop.name,
                    SymbolKind.Property,
                    ASTUtils.astNodeToRange(prop),
                    ASTUtils.astNodeToRange(prop),
                    "${prop.type.nameWithoutPackage} ${prop.name}",
                    prop.type.name,
                    null,
                    prop.modifiers,
                    uri,
                    prop.type.name
                ))
            }

            // Methods
            for (MethodNode method : clazz.methods) {
                if (method.isSynthetic()) continue
                String baseDesc = buildDescriptor(clazz, method)
                String desc = baseDesc
                
                // Handle dynamic overloads (collision prevention)
                int count = overloadCounts.getOrDefault(baseDesc, 0)
                if (count > 0) {
                    desc = "${baseDesc}#${count}"
                }
                overloadCounts[baseDesc] = count + 1

                symbols.add(new SymbolInfo(
                    desc,
                    containerDescriptor,
                    method.name,
                    SymbolKind.Method,
                    ASTUtils.astNodeToRange(method),
                    ASTUtils.astNodeToRange(method),
                    buildMethodSignature(method),
                    method.returnType.name,
                    null,
                    method.modifiers,
                    uri,
                    "Method"
                ))
            }
        }
        return symbols
    }

    static List<LocalSymbolInfo> buildLocals(String uri, List<ClassNode> classNodes) {
        List<LocalSymbolInfo> locals = []
        for (ClassNode clazz : classNodes) {
            for (MethodNode method : clazz.methods) {
                if (method.isSynthetic()) continue
                
                // Parameters
                if (method.parameters) {
                    for (Parameter param : method.parameters) {
                        locals.add(new LocalSymbolInfo(
                            param.name,
                            SymbolKind.Variable,
                            ASTUtils.astNodeToRange(param),
                            "${param.type.nameWithoutPackage} ${param.name}",
                            param.type.name,
                            uri
                        ))
                    }
                }

                if (method.code) {
                    method.code.visit(new CodeVisitorSupport() {
                        @Override
                        void visitDeclarationExpression(DeclarationExpression expression) {
                            super.visitDeclarationExpression(expression)
                            def left = expression.leftExpression
                            if (left instanceof VariableExpression) {
                                locals.add(new LocalSymbolInfo(
                                    left.name,
                                    SymbolKind.Variable,
                                    ASTUtils.astNodeToRange(left),
                                    "${left.type.nameWithoutPackage} ${left.name}",
                                    left.type.name,
                                    uri
                                ))
                            }
                        }
                    })
                }
            }
        }
        return locals
    }

    static List<ReferenceInfo> buildReferences(String uri, List<ClassNode> classNodes) {
        List<ReferenceInfo> references = []
        for (ClassNode clazz : classNodes) {
            for (MethodNode method : clazz.methods) {
                if (method.isSynthetic() || !method.code) continue

                method.code.visit(new CodeVisitorSupport() {
                    @Override
                    void visitVariableExpression(VariableExpression expression) {
                        super.visitVariableExpression(expression)
                        if (expression.name != "this" && expression.name != "super") {
                            references.add(new ReferenceInfo(expression.name, uri, ASTUtils.astNodeToRange(expression)))
                        }
                    }

                    @Override
                    void visitMethodCallExpression(org.codehaus.groovy.ast.expr.MethodCallExpression call) {
                        super.visitMethodCallExpression(call)
                        if (call.methodAsString) {
                            references.add(new ReferenceInfo(call.methodAsString, uri, ASTUtils.astNodeToRange(call.method)))
                        }
                    }

                    @Override
                    void visitPropertyExpression(org.codehaus.groovy.ast.expr.PropertyExpression expression) {
                        super.visitPropertyExpression(expression)
                        if (expression.propertyAsString) {
                            references.add(new ReferenceInfo(expression.propertyAsString, uri, ASTUtils.astNodeToRange(expression.property)))
                        }
                    }
                })
            }
        }
        return references
    }

    static List<kingsk.grails.lsp.core.analysis.GrailsRelationshipItem> buildRelationships(String uri, List<ClassNode> classNodes) {
        return GrailsRelationshipExtractor.extractRelationships(uri, classNodes)
    }

    static String buildDescriptor(ClassNode clazz, MethodNode method) {
        String params = ""
        if (method.parameters) {
            params = method.parameters.collect { it.type.name }.join(",")
        }
        return "${clazz.name}#${method.name}(${params})"
    }

    static String buildDescriptor(ClassNode clazz, FieldNode field) {
        return "${clazz.name}#${field.name}"
    }

    static String buildDescriptor(ClassNode clazz, PropertyNode property) {
        return "${clazz.name}#${property.name}"
    }

    private static String buildMethodSignature(MethodNode method) {
        String params = ""
        if (method.parameters) {
            params = method.parameters.collect { "${it.type.nameWithoutPackage} ${it.name}" }.join(", ")
        }
        return "${method.returnType.nameWithoutPackage} ${method.name}(${params})"
    }

    private static GrailsArtifactType determineArtifactType(ClassNode clazz) {
        String pkg = clazz.packageName ?: ""
        String name = clazz.nameWithoutPackage ?: ""
        if (pkg.contains("controllers") && name.endsWith("Controller")) return GrailsArtifactType.CONTROLLER
        if (pkg.contains("services") && name.endsWith("Service")) return GrailsArtifactType.SERVICE
        if (pkg.contains("domain")) return GrailsArtifactType.DOMAIN
        return GrailsArtifactType.UNKNOWN
    }
}
