package kingsk.grails.lsp.core.visitor

import groovy.transform.CompileStatic
import kingsk.grails.lsp.GrailsService
import kingsk.grails.lsp.context.ASTAccessor
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.utils.position.PositionHelper
import kingsk.grails.lsp.utils.position.RangeHelper
import kingsk.grails.lsp.core.compiler.GrailsCompiler
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.classgen.BytecodeExpression
import org.codehaus.groovy.control.SourceUnit
import org.eclipse.lsp4j.Position
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

/**
 * Robust AST Visitor that implements the ASTAccessor interface.
 * ENSURES: Snapshots are consistent via Generation Swapping in ProjectContextImpl.
 */
@Slf4j
@CompileStatic
class GrailsASTVisitor extends ClassCodeVisitorSupport implements ASTAccessor {
    private SourceUnit sourceUnit
    private final Stack<ASTNode> stack = new Stack<>()

    // Core AST Data Stores
    final Map<String, Set<ASTNode>> nodesByURI = new ConcurrentHashMap<>()
    final Map<String, Set<ClassNode>> classNodesByURI = new ConcurrentHashMap<>()
    final Map<String, ModuleNode> moduleNodesByURI = new ConcurrentHashMap<>()
    final Map<ASTLookupKey, ASTNodeLookupData> lookup = new ConcurrentHashMap<>()
    final Map<String, Map<Integer, Set<ASTNode>>> nodesByLineIndex = new ConcurrentHashMap<>()

    Map<String, Set<ClassNode>> getAllClassNodes() {
        return classNodesByURI
    }

    boolean isEmpty() {
        return nodesByURI.isEmpty()
    }

    // Memory management
    private static final int MAX_FILES_IN_MEMORY = 50
    private final Map<String, Long> fileAccessTimes = new ConcurrentHashMap<>()

    private static class ASTLookupKey {
        ASTNode node
        boolean equals(Object o) {
            if (this.is(o)) return true
            if (!(o instanceof ASTLookupKey)) return false
            ASTLookupKey that = (ASTLookupKey) o
            return node == that.node
        }
        int hashCode() { return System.identityHashCode(node) }
    }

    private static class ASTNodeLookupData {
        ASTNode parent
        String uri
    }

    final GrailsService service

    @Override
    protected SourceUnit getSourceUnit() {
        return this.sourceUnit
    }

    SourceUnit getLiveSourceUnit() {
        return this.sourceUnit
    }

    GrailsASTVisitor() { this.service = null }
    GrailsASTVisitor(GrailsService service) { this.service = service }

    private void pushASTNode(ASTNode node) {
        if (node == null) return
        String uri = TextFile.normalizePath(sourceUnit.name)
        boolean isSynthetic = (node instanceof AnnotatedNode && node.synthetic) ?: false
        ASTNode parent = (stack.size() > 0) ? stack.lastElement() : null

        lookup.put(new ASTLookupKey(node: node), new ASTNodeLookupData(uri: uri, parent: parent))

        if (!isSynthetic) {
            nodesByURI.computeIfAbsent(uri, k -> [] as Set).add(node)
            if (node.lineNumber > 0) {
                def lineMap = nodesByLineIndex.computeIfAbsent(uri) { new ConcurrentHashMap<Integer, Set<ASTNode>>() }
                for (int line = node.lineNumber; line <= Math.max(node.lineNumber, node.lastLineNumber); line++) {
                    lineMap.computeIfAbsent(line, k -> [] as Set).add(node)
                }
            }
        }
        stack.add(node)
    }

    private void popASTNode() { if (!stack.isEmpty()) stack.pop() }

    void invalidateVisitor() {
        log.info "[AST] Invalidating visitor state"
        nodesByURI.clear(); classNodesByURI.clear(); moduleNodesByURI.clear()
        nodesByLineIndex.clear(); lookup.clear(); stack.clear()
    }

    void copyFrom(GrailsASTVisitor other) {
        if (!other) return
        this.nodesByURI.putAll(other.nodesByURI)
        this.classNodesByURI.putAll(other.classNodesByURI)
        if (other.moduleNodesByURI) {
            this.moduleNodesByURI.putAll(other.moduleNodesByURI)
        }
        this.lookup.putAll(other.lookup)
        this.nodesByLineIndex.putAll(other.nodesByLineIndex)
    }

    void removeFileWithDependencies(String uri) {
        if (!uri) return
        nodesByURI.remove(uri)
        classNodesByURI.remove(uri)
        moduleNodesByURI.remove(uri)
        nodesByLineIndex.remove(uri)
        lookup.entrySet().removeIf { it.value.uri == uri }
        String norm = TextFile.normalizePath(uri)
        if (norm && norm != uri) {
            nodesByURI.remove(norm)
            classNodesByURI.remove(norm)
            moduleNodesByURI.remove(norm)
            nodesByLineIndex.remove(norm)
            lookup.entrySet().removeIf { it.value.uri == norm }
        }
    }

    @Override
    Set<ClassNode> getClassNodes(String uri) {
        if (!uri) return classNodesByURI.values().flatten() as Set<ClassNode>
        Set<ClassNode> direct = classNodesByURI.get(uri)
        if (direct != null) return direct
        String norm = TextFile.normalizePath(uri)
        if (norm && norm != uri) {
            Set<ClassNode> normalized = classNodesByURI.get(norm)
            if (normalized != null) return normalized
        }
        return Collections.emptySet()
    }

    @Override
    Set<ClassNode> getClassNodes() { return getClassNodes(null) }

    @Override
    Set<ASTNode> getNodes(String uri) {
        if (!uri) return nodesByURI.values().stream().flatMap(Set::stream).collect(Collectors.toSet())
        Set<ASTNode> direct = nodesByURI.get(uri)
        if (direct != null) return direct
        String norm = TextFile.normalizePath(uri)
        if (norm && norm != uri) {
            Set<ASTNode> normalized = nodesByURI.get(norm)
            if (normalized != null) return normalized
        }
        return Collections.emptySet()
    }

    @Override
    Set<ASTNode> getNodes() { return getNodes(null) }

    @Override
    ASTNode getNodeAtPosition(String uri, Position position) {
        def lineIndex = nodesByLineIndex.get(uri)
        if (!lineIndex && uri) {
            String norm = TextFile.normalizePath(uri)
            if (norm && norm != uri) {
                lineIndex = nodesByLineIndex.get(norm)
            }
        }
        if (!lineIndex) return null
        Set<ASTNode> candidates = lineIndex.getOrDefault(position.line + 1, Collections.emptySet())
        def matches = candidates.findAll { RangeHelper.isPositionWithinNode(it, position) }
        log.info("[GET_NODE_DEBUG] uri=${uri} line=${position.line + 1} pos=${position.character} candidates=${candidates.collect { "${it.class.simpleName}(${it.text})[${it.lineNumber}:${it.columnNumber}-${it.lastLineNumber}:${it.lastColumnNumber}]" }} matches=${matches.collect { "${it.class.simpleName}(${it.text})" }}")
        return findMostSpecificNode(matches)
    }

    @Override
    ASTNode getNodeAtLineAndColumn(String uri, int line, int column) {
        return getNodeAtPosition(uri, new Position(line, column))
    }

    @Override
    ASTNode getParent(ASTNode child) {
        if (child == null) return null
        ASTNode p = lookup[new ASTLookupKey(node: child)]?.parent
        return (p != null && !p.is(child)) ? p : null
    }

    @Override
    String getURI(ASTNode node) {
        return node ? lookup[new ASTLookupKey(node: node)]?.uri : null
    }

    private ASTNode findMostSpecificNode(Set<ASTNode> candidates) {
        if (candidates.empty) return null
        return candidates.sort { n1, n2 ->
            int c = PositionHelper.compareStartPositions(n1, n2)
            if (c != 0) return c
            c = PositionHelper.compareEndPositions(n1, n2)
            if (c != 0) return c
            int s1 = getNodeTypeSpecificity(n1)
            int s2 = getNodeTypeSpecificity(n2)
            if (s1 != s2) return s2 - s1
            return getParent(n2) == n1 ? 1 : getParent(n1) == n2 ? -1 : 0
        }.first()
    }

    private static int getNodeTypeSpecificity(ASTNode node) {
        if (node instanceof org.codehaus.groovy.ast.expr.Expression) return 3
        if (node instanceof org.codehaus.groovy.ast.stmt.Statement) return 2
        if (node instanceof MethodNode || node instanceof FieldNode || node instanceof PropertyNode) return 1
        return 0
    }

    void visitSourceUnit(SourceUnit unit) {
        if (!unit) return
        String uri = TextFile.normalizePath(unit.name)
        nodesByURI.remove(uri); classNodesByURI.remove(uri); nodesByLineIndex.remove(uri)
        lookup.entrySet().removeIf { it.value.uri == uri }

        nodesByURI[uri] = [] as Set
        classNodesByURI[uri] = [] as Set
        sourceUnit = unit
        visitModule(unit.AST)
        sourceUnit = null
    }

    void visitModule(ModuleNode node) {
        if (!node) return
        moduleNodesByURI[TextFile.normalizePath(sourceUnit.name)] = node
        pushASTNode(node)
        try {
            visitImports(node)
            node.classes.each { visitClass(it) }
            boolean hasScriptClass = node.classes.any { it.isScript() }
            if (!hasScriptClass && node.statementBlock != null && !node.statementBlock.empty) {
                visitBlockStatement(node.statementBlock)
            }
            node.methods?.each { visitMethod(it) }
        } finally {
            popASTNode()
        }
    }

    void visitClass(ClassNode node) {
        if (!node) return
        classNodesByURI[TextFile.normalizePath(sourceUnit.name)].add(node)
        pushASTNode(node); try { super.visitClass(node) } finally { popASTNode() }
    }

    // Standard visit overrides...
    void visitImports(ModuleNode node) {
        if (!node) return
        node.imports?.each { n -> pushASTNode(n); visitAnnotations(n); n.visit(this); popASTNode() }
    }
    void visitPackage(PackageNode node) { if (!node) return; pushASTNode(node); try { super.visitPackage(node) } finally { popASTNode() } }
    void visitMethod(MethodNode node) { if (!node) return; pushASTNode(node); try { super.visitMethod(node); node.parameters.each { visitParameter(it) } } finally { popASTNode() } }
    protected void visitParameter(Parameter node) { if (!node) return; pushASTNode(node); try { super.visitAnnotations(node) } finally { popASTNode() } }
    void visitField(FieldNode node) { if (!node) return; pushASTNode(node); try { super.visitField(node) } finally { popASTNode() } }
    void visitProperty(PropertyNode node) { if (!node) return; pushASTNode(node); try { super.visitProperty(node) } finally { popASTNode() } }
    void visitBlockStatement(BlockStatement node) { if (!node) return; pushASTNode(node); try { super.visitBlockStatement(node) } finally { popASTNode() } }
    void visitReturnStatement(ReturnStatement node) { if (!node) return; pushASTNode(node); try { super.visitReturnStatement(node) } finally { popASTNode() } }
    void visitExpressionStatement(ExpressionStatement node) { if (!node) return; pushASTNode(node); try { super.visitExpressionStatement(node) } finally { popASTNode() } }
    void visitMethodCallExpression(MethodCallExpression node) { if (!node) return; pushASTNode(node); try { super.visitMethodCallExpression(node) } finally { popASTNode() } }
    void visitConstructorCallExpression(ConstructorCallExpression node) { if (!node) return; pushASTNode(node); try { super.visitConstructorCallExpression(node) } finally { popASTNode() } }
    void visitVariableExpression(VariableExpression node) { if (!node) return; pushASTNode(node); try { super.visitVariableExpression(node) } finally { popASTNode() } }
    void visitDeclarationExpression(DeclarationExpression node) { if (!node) return; pushASTNode(node); try { super.visitDeclarationExpression(node) } finally { popASTNode() } }
    void visitBinaryExpression(BinaryExpression node) { if (!node) return; pushASTNode(node); try { super.visitBinaryExpression(node) } finally { popASTNode() } }
    void visitPropertyExpression(PropertyExpression node) { if (!node) return; pushASTNode(node); try { super.visitPropertyExpression(node) } finally { popASTNode() } }
    void visitClosureExpression(ClosureExpression node) { if (!node) return; pushASTNode(node); try { super.visitClosureExpression(node) } finally { popASTNode() } }
}
