package kingsk.grails.lsp.index

import groovy.transform.CompileStatic
import kingsk.grails.lsp.core.analysis.AnalysisEvidence
import kingsk.grails.lsp.core.analysis.AnalysisOriginKind
import kingsk.grails.lsp.core.analysis.AnalysisRange
import kingsk.grails.lsp.core.analysis.AnalysisResolutionKind
import kingsk.grails.lsp.core.analysis.GrailsRelationshipItem
import kingsk.grails.lsp.core.analysis.RelationshipEndpoint
import kingsk.grails.lsp.core.analysis.RelationshipType
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.ReturnStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression
import org.codehaus.groovy.ast.expr.TupleExpression

@CompileStatic
class GrailsRelationshipExtractor {

    static List<GrailsRelationshipItem> extractRelationships(String uri, List<ClassNode> classNodes) {
        if (!classNodes) return []
        List<GrailsRelationshipItem> results = []

        for (ClassNode clazz : classNodes) {
            extractDomainAssociations(uri, clazz, results)
            extractInjections(uri, clazz, results)
            extractControllerConventionsAndViews(uri, clazz, results)
            extractUrlMappings(uri, clazz, results)
            extractTagLibTags(uri, clazz, results)
        }

        return results
    }

    private static void extractDomainAssociations(String uri, ClassNode clazz, List<GrailsRelationshipItem> results) {
        // Inheritance
        if (clazz.superClass != null &&
            clazz.superClass.name != "java.lang.Object" &&
            clazz.superClass.name != "groovy.lang.GroovyObjectSupport" &&
            clazz.superClass.name != "java.lang.Enum") {
            String superSimpleName = clazz.superClass.nameWithoutPackage
            RelationshipEndpoint src = new RelationshipEndpoint(clazz.nameWithoutPackage, "Domain", uri, toAnalysisRange(clazz), clazz.name, null)
            RelationshipEndpoint tgt = new RelationshipEndpoint(superSimpleName, "Domain", null, null, clazz.superClass.name, null)
            results.add(new GrailsRelationshipItem(
                "rel:${clazz.name}:extends:${clazz.superClass.name}".toString(),
                RelationshipType.DOMAIN_ASSOCIATION.value,
                src, tgt,
                AnalysisOriginKind.DECLARATION.value,
                AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
                new AnalysisEvidence(uri, clazz.name, "inheritance", null),
                [associationType: "inheritance"]
            ))
        }

        // hasMany, belongsTo, hasOne
        List<String> assocTypes = ["hasMany", "belongsTo", "hasOne"]
        for (String assocName : assocTypes) {
            Expression initExpr = findFieldOrPropertyExpression(clazz, assocName)
            if (initExpr instanceof MapExpression) {
                MapExpression mapExpr = (MapExpression) initExpr
                for (MapEntryExpression entry : mapExpr.mapEntryExpressions) {
                    String propName = entry.keyExpression?.text ?: "unknown"
                    Expression valExpr = entry.valueExpression
                    if (valExpr instanceof ClassExpression) {
                        ClassNode targetType = ((ClassExpression) valExpr).type
                        String targetSimple = targetType.nameWithoutPackage
                        RelationshipEndpoint src = new RelationshipEndpoint(clazz.nameWithoutPackage, "Domain", uri, toAnalysisRange(entry), clazz.name, propName)
                        RelationshipEndpoint tgt = new RelationshipEndpoint(targetSimple, "Domain", null, null, targetType.name, null)
                        results.add(new GrailsRelationshipItem(
                            "rel:${clazz.name}:${assocName}:${propName}".toString(),
                            RelationshipType.DOMAIN_ASSOCIATION.value,
                            src, tgt,
                            AnalysisOriginKind.DECLARATION.value,
                            AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
                            new AnalysisEvidence(uri, clazz.name, "${assocName}:${propName}".toString(), null),
                            [associationType: assocName, property: propName]
                        ))
                    } else {
                        // Dynamic unknown target
                        RelationshipEndpoint src = new RelationshipEndpoint(clazz.nameWithoutPackage, "Domain", uri, toAnalysisRange(entry), clazz.name, propName)
                        RelationshipEndpoint tgt = new RelationshipEndpoint("Unknown", "Unknown", null, null, null, null)
                        results.add(new GrailsRelationshipItem(
                            "rel:${clazz.name}:${assocName}:${propName}:unresolved".toString(),
                            RelationshipType.DOMAIN_ASSOCIATION.value,
                            src, tgt,
                            AnalysisOriginKind.DECLARATION.value,
                            AnalysisResolutionKind.UNRESOLVED.value,
                            new AnalysisEvidence(uri, clazz.name, "${assocName}:${propName}".toString(), null),
                            [associationType: assocName, property: propName, unknownReason: "Dynamic or non-constant expression in ${assocName} map: ${valExpr?.text}".toString()]
                        ))
                    }
                }
            }
        }
    }

    private static void extractInjections(String uri, ClassNode clazz, List<GrailsRelationshipItem> results) {
        Set<String> processedProps = new HashSet<>()
        for (PropertyNode prop : clazz.properties) {
            processPropertyOrFieldInjection(uri, clazz, prop.name, prop, processedProps, results)
        }
        for (FieldNode field : clazz.fields) {
            if (!field.synthetic) {
                processPropertyOrFieldInjection(uri, clazz, field.name, field, processedProps, results)
            }
        }
    }

    private static void processPropertyOrFieldInjection(
        String uri,
        ClassNode clazz,
        String name,
        ASTNode node,
        Set<String> processed,
        List<GrailsRelationshipItem> results
    ) {
        if (!name || !processed.add(name)) return

        if (name.endsWith("Service") && name.length() > "Service".length()) {
            String serviceName = Character.toUpperCase(name.charAt(0)).toString() + name.substring(1)
            RelationshipEndpoint src = new RelationshipEndpoint(clazz.nameWithoutPackage, determineEndpointKind(clazz, uri), uri, toAnalysisRange(node), clazz.name, name)
            RelationshipEndpoint tgt = new RelationshipEndpoint(serviceName, "Service", null, null, null, null)
            results.add(new GrailsRelationshipItem(
                "rel:${clazz.name}:inject:${name}".toString(),
                RelationshipType.INJECTION.value,
                src, tgt,
                AnalysisOriginKind.CAPABILITY_ADAPTER.value,
                AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
                new AnalysisEvidence(uri, clazz.name, "injection:${name}".toString(), null),
                [serviceBean: name]
            ))
        } else if (name in ["grailsApplication", "sessionFactory", "transactionManager", "grailsLinkGenerator", "messageSource"]) {
            RelationshipEndpoint src = new RelationshipEndpoint(clazz.nameWithoutPackage, determineEndpointKind(clazz, uri), uri, toAnalysisRange(node), clazz.name, name)
            RelationshipEndpoint tgt = new RelationshipEndpoint(name, "Bean", null, null, null, null)
            results.add(new GrailsRelationshipItem(
                "rel:${clazz.name}:inject:${name}".toString(),
                RelationshipType.INJECTION.value,
                src, tgt,
                AnalysisOriginKind.CAPABILITY_ADAPTER.value,
                AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
                new AnalysisEvidence(uri, clazz.name, "injection:${name}".toString(), null),
                [frameworkBean: name]
            ))
        }
    }

    private static void extractControllerConventionsAndViews(String uri, ClassNode clazz, List<GrailsRelationshipItem> results) {
        String cName = clazz.nameWithoutPackage
        if (!cName.endsWith("Controller")) return

        String logicalCtrl = cName.substring(0, cName.length() - "Controller".length())
        if (!logicalCtrl.isEmpty()) {
            logicalCtrl = Character.toLowerCase(logicalCtrl.charAt(0)).toString() + (logicalCtrl.length() > 1 ? logicalCtrl.substring(1) : "")
        }

        for (MethodNode method : clazz.methods) {
            if (method.synthetic || method.static || method.name.startsWith("\$")) continue
            if (method.name in ["getMetaClass", "setMetaClass", "invokeMethod", "getProperty", "setProperty"]) continue

            String actionName = method.name
            String pattern = "/${logicalCtrl}/${actionName}".toString()

            RelationshipEndpoint routeSrc = new RelationshipEndpoint(pattern, "Route", uri, null, null, null)
            RelationshipEndpoint actionTgt = new RelationshipEndpoint("${cName}#${actionName}".toString(), "Action", uri, toAnalysisRange(method), clazz.name, actionName)

            // Convention route
            results.add(new GrailsRelationshipItem(
                "rel:${clazz.name}#${actionName}:route:${pattern}".toString(),
                RelationshipType.ROUTE_TO_ACTION.value,
                routeSrc, actionTgt,
                AnalysisOriginKind.CAPABILITY_ADAPTER.value,
                AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
                new AnalysisEvidence(uri, clazz.name, "route:${pattern}".toString(), null),
                [pattern: pattern, controller: logicalCtrl, action: actionName]
            ))

            // Convention view
            RelationshipEndpoint convViewTgt = new RelationshipEndpoint("${actionName}.gsp".toString(), "View", null, null, null, actionName)
            results.add(new GrailsRelationshipItem(
                "rel:${clazz.name}#${actionName}:conventionView:${actionName}".toString(),
                RelationshipType.RENDERS_VIEW.value,
                actionTgt, convViewTgt,
                AnalysisOriginKind.CAPABILITY_ADAPTER.value,
                AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
                new AnalysisEvidence(uri, clazz.name, "conventionView:${actionName}".toString(), null),
                [conventionView: true, view: actionName]
            ))

            // Method body explicit / dynamic render calls
            if (method.code != null) {
                List<MethodCallExpression> calls = []
                findMethodCalls(method.code, calls)
                for (MethodCallExpression call : calls) {
                    String methodName = call.methodAsString ?: getExprText(call.method)
                    if (methodName == "render") {
                        List<MapEntryExpression> entries = extractMapEntries(call.arguments)
                        for (MapEntryExpression entry : entries) {
                            String k = getExprText(entry.keyExpression)
                            if (k == "view") {
                                Expression val = entry.valueExpression
                                if (val instanceof ConstantExpression) {
                                    String viewName = getExprText(val)
                                    String gspName = viewName.endsWith(".gsp") ? viewName : "${viewName}.gsp".toString()
                                    RelationshipEndpoint expViewTgt = new RelationshipEndpoint(gspName, "View", null, toAnalysisRange(entry), null, viewName)
                                    results.add(new GrailsRelationshipItem(
                                        "rel:${clazz.name}#${actionName}:rendersView:${viewName}".toString(),
                                        RelationshipType.RENDERS_VIEW.value,
                                        actionTgt, expViewTgt,
                                        AnalysisOriginKind.DECLARATION.value,
                                        AnalysisResolutionKind.DECLARATION_BACKED.value,
                                        new AnalysisEvidence(uri, clazz.name, "render:view:${viewName}".toString(), null),
                                        [view: viewName]
                                    ))
                                } else {
                                    RelationshipEndpoint unkViewTgt = new RelationshipEndpoint("Unknown", "Unknown", null, toAnalysisRange(entry), null, null)
                                    results.add(new GrailsRelationshipItem(
                                        "rel:${clazz.name}#${actionName}:rendersView:dynamic:${val?.text}".toString(),
                                        RelationshipType.RENDERS_VIEW.value,
                                        actionTgt, unkViewTgt,
                                        AnalysisOriginKind.DECLARATION.value,
                                        AnalysisResolutionKind.UNRESOLVED.value,
                                        new AnalysisEvidence(uri, clazz.name, "render:view:dynamic", null),
                                        [dynamicView: true, unknownReason: "Dynamic view expression: ${val?.text}".toString()]
                                    ))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void extractUrlMappings(String uri, ClassNode clazz, List<GrailsRelationshipItem> results) {
        String name = clazz.nameWithoutPackage
        if (!name.endsWith("UrlMappings") && name != "UrlMappings" && clazz.getField("mappings") == null && clazz.getProperty("mappings") == null) {
            return
        }

        Expression mappingsExpr = findFieldOrPropertyExpression(clazz, "mappings")
        if (mappingsExpr instanceof ClosureExpression) {
            ClosureExpression ce = (ClosureExpression) mappingsExpr
            if (ce.code != null) {
                List<MethodCallExpression> calls = []
                findMethodCalls(ce.code, calls)
                for (MethodCallExpression call : calls) {
                    String routePattern = call.methodAsString ?: getExprText(call.method)
                    if (routePattern != null && routePattern.startsWith("/")) {
                        if (routePattern.contains("\$controller") || routePattern.contains("\${controller}") || routePattern.contains("\$action") || routePattern.contains("\${action}")) {
                            RelationshipEndpoint routeSrc = new RelationshipEndpoint(routePattern, "Route", uri, toAnalysisRange(call), null, null)
                            RelationshipEndpoint dynTgt = new RelationshipEndpoint("DynamicAction", "Unknown", null, null, null, null)
                            results.add(new GrailsRelationshipItem(
                                "rel:urlmapping:wildcard:${routePattern}".toString(),
                                RelationshipType.ROUTE_TO_ACTION.value,
                                routeSrc, dynTgt,
                                AnalysisOriginKind.DECLARATION.value,
                                AnalysisResolutionKind.UNRESOLVED.value,
                                new AnalysisEvidence(uri, clazz.name, "urlmapping:${routePattern}".toString(), null),
                                [dynamicRoute: true, pattern: routePattern, unknownReason: "Wildcard route matches controllers and actions dynamically at runtime"]
                            ))
                        } else {
                            String ctrl = null
                            String act = null
                            List<MapEntryExpression> entries = extractMapEntries(call.arguments)
                            for (MapEntryExpression entry : entries) {
                                String k = getExprText(entry.keyExpression)
                                if (k == "controller") {
                                    ctrl = getExprText(entry.valueExpression)
                                } else if (k == "action") {
                                    act = getExprText(entry.valueExpression)
                                }
                            }
                            if (ctrl != null && act != null) {
                                String ctrlClass = Character.toUpperCase(ctrl.charAt(0)).toString() + ctrl.substring(1) + "Controller"
                                String targetAction = "${ctrlClass}#${act}".toString()
                                RelationshipEndpoint routeSrc = new RelationshipEndpoint(routePattern, "Route", uri, toAnalysisRange(call), null, null)
                                RelationshipEndpoint actionTgt = new RelationshipEndpoint(targetAction, "Action", null, null, ctrlClass, act)
                                results.add(new GrailsRelationshipItem(
                                    "rel:urlmapping:${routePattern}->${targetAction}".toString(),
                                    RelationshipType.ROUTE_TO_ACTION.value,
                                    routeSrc, actionTgt,
                                    AnalysisOriginKind.DECLARATION.value,
                                    AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
                                    new AnalysisEvidence(uri, clazz.name, "urlmapping:${routePattern}".toString(), null),
                                    [pattern: routePattern, controller: ctrl, action: act]
                                ))
                            }
                        }
                    }
                }
            }
        }
    }

    private static void extractTagLibTags(String uri, ClassNode clazz, List<GrailsRelationshipItem> results) {
        String name = clazz.nameWithoutPackage
        if (!name.endsWith("TagLib") && !uri.contains("/taglib/")) return

        String namespace = "g"
        Expression nsExpr = findFieldOrPropertyExpression(clazz, "namespace")
        if (nsExpr instanceof ConstantExpression) {
            namespace = ((ConstantExpression) nsExpr).text
        }

        for (PropertyNode prop : clazz.properties) {
            if (prop.name in ["metaClass", "namespace", "class"]) continue
            String tagName = prop.name
            String fullTag = "${namespace}:${tagName}".toString()
            RelationshipEndpoint src = new RelationshipEndpoint(clazz.nameWithoutPackage, "TagLib", uri, toAnalysisRange(prop), clazz.name, tagName)
            RelationshipEndpoint tgt = new RelationshipEndpoint(fullTag, "Tag", uri, toAnalysisRange(prop), null, tagName)
            results.add(new GrailsRelationshipItem(
                "rel:${clazz.name}:tag:${fullTag}".toString(),
                RelationshipType.TAG_CALL.value,
                src, tgt,
                AnalysisOriginKind.DECLARATION.value,
                AnalysisResolutionKind.DECLARATION_BACKED.value,
                new AnalysisEvidence(uri, clazz.name, "tag:${fullTag}".toString(), null),
                [namespace: namespace, tagName: tagName]
            ))
        }
    }

    private static void findMethodCalls(Statement stmt, List<MethodCallExpression> collector) {
        if (stmt == null) return
        if (stmt instanceof BlockStatement) {
            for (Statement s : ((BlockStatement) stmt).statements) {
                findMethodCalls(s, collector)
            }
        } else if (stmt instanceof ExpressionStatement) {
            findMethodCallsInExpr(((ExpressionStatement) stmt).expression, collector)
        } else if (stmt instanceof ReturnStatement) {
            findMethodCallsInExpr(((ReturnStatement) stmt).expression, collector)
        }
    }

    private static void findMethodCallsInExpr(Expression expr, List<MethodCallExpression> collector) {
        if (expr == null) return
        if (expr instanceof MethodCallExpression) {
            collector.add((MethodCallExpression) expr)
            findMethodCallsInExpr(((MethodCallExpression) expr).arguments, collector)
        } else if (expr instanceof TupleExpression) {
            for (Expression child : ((TupleExpression) expr).expressions) {
                findMethodCallsInExpr(child, collector)
            }
        } else if (expr instanceof ClosureExpression) {
            findMethodCalls(((ClosureExpression) expr).code, collector)
        }
    }

    private static Expression findFieldOrPropertyExpression(ClassNode clazz, String name) {
        FieldNode field = clazz.getField(name)
        if (field != null) {
            if (field.initialValueExpression != null) return field.initialValueExpression
            if (field.initialExpression != null) return field.initialExpression
        }
        PropertyNode prop = clazz.getProperty(name)
        if (prop != null) {
            if (prop.initialExpression != null) return prop.initialExpression
            if (prop.field?.initialValueExpression != null) return prop.field.initialValueExpression
        }
        return null
    }

    private static List<MapEntryExpression> extractMapEntries(Expression expr) {
        if (expr == null) return []
        if (expr instanceof MapExpression) {
            return ((MapExpression) expr).mapEntryExpressions
        } else if (expr instanceof NamedArgumentListExpression) {
            return ((NamedArgumentListExpression) expr).mapEntryExpressions
        } else if (expr instanceof TupleExpression) {
            List<MapEntryExpression> list = []
            for (Expression child : ((TupleExpression) expr).expressions) {
                list.addAll(extractMapEntries(child))
            }
            return list
        }
        return []
    }

    private static String getExprText(Expression expr) {
        if (expr == null) return null
        if (expr instanceof ConstantExpression) {
            Object v = ((ConstantExpression) expr).value
            return v != null ? v.toString() : ((ConstantExpression) expr).text
        }
        return expr.text
    }

    private static String determineEndpointKind(ClassNode clazz, String uri) {
        String name = clazz.nameWithoutPackage
        if (name.endsWith("Controller") || uri.contains("/controllers/")) return "Controller"
        if (name.endsWith("Service") || uri.contains("/services/")) return "Service"
        if (name.endsWith("TagLib") || uri.contains("/taglib/")) return "TagLib"
        if (uri.contains("/domain/")) return "Domain"
        return "Class"
    }

    private static AnalysisRange toAnalysisRange(ASTNode node) {
        if (node == null || node.lineNumber < 0) return null
        int sl = Math.max(0, node.lineNumber - 1)
        int sc = Math.max(0, node.columnNumber - 1)
        int el = node.lastLineNumber > 0 ? Math.max(0, node.lastLineNumber - 1) : sl
        int ec = node.lastColumnNumber > 0 ? Math.max(0, node.lastColumnNumber - 1) : sc
        return new AnalysisRange(sl, sc, el, ec)
    }
}
