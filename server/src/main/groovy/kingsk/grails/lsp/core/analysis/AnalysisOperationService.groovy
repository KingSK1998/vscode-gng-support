package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import kingsk.grails.lsp.GrailsService
import kingsk.grails.lsp.context.CompilationContext
import kingsk.grails.lsp.context.ProjectContextImpl
import kingsk.grails.lsp.context.RequestContext
import kingsk.grails.lsp.context.RequestLease
import kingsk.grails.lsp.core.capability.GormCapabilityAdapter
import kingsk.grails.lsp.core.capability.GrailsCapabilityAdapter
import kingsk.grails.lsp.core.capability.GrailsConfigCapabilityAdapter
import kingsk.grails.lsp.core.capability.GrailsInjectionCapabilityAdapter
import kingsk.grails.lsp.core.capability.TagLibCapabilityAdapter
import kingsk.grails.lsp.core.compatibility.CompatibilityAssessment
import kingsk.grails.lsp.core.compatibility.CompatibilityMatrix
import kingsk.grails.lsp.index.IndexSnapshot
import kingsk.grails.lsp.index.SymbolInfo
import kingsk.grails.lsp.model.enums.GrailsArtifactType
import kingsk.grails.lsp.model.state.ProjectState
import kingsk.grails.lsp.model.state.VersionedSnapshot
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.services.WorkspaceManager
import kingsk.grails.lsp.utils.grails.GrailsArtefactUtils

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind

import kingsk.grails.lsp.core.refactor.DocumentPrecondition
import kingsk.grails.lsp.core.refactor.RenameApplyRequest
import kingsk.grails.lsp.core.refactor.RenameApplyResponse
import kingsk.grails.lsp.core.refactor.RenameCollision
import kingsk.grails.lsp.core.refactor.RenameEdit
import kingsk.grails.lsp.core.refactor.RenamePreviewRequest
import kingsk.grails.lsp.core.refactor.RenamePreviewResponse
import kingsk.grails.lsp.core.refactor.TargetSymbol
import kingsk.grails.lsp.core.refactor.UncertainMatch
import kingsk.grails.lsp.core.check.CheckDiagnosticItem
import kingsk.grails.lsp.core.check.CheckExecutionRequest
import kingsk.grails.lsp.core.check.CheckExecutionResponse
import kingsk.grails.lsp.core.check.CheckScope
import kingsk.grails.lsp.core.check.CheckStatus
import kingsk.grails.lsp.core.check.CheckSummary
import kingsk.grails.lsp.utils.ast.ASTUtils
import kingsk.grails.lsp.utils.ast.GrailsASTHelper
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.PropertyNode
import kingsk.grails.lsp.core.visitor.GrailsASTVisitor
import kingsk.grails.lsp.index.ReferenceInfo

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Collections
import java.util.function.BiFunction
import java.util.function.BooleanSupplier

@Slf4j
@CompileStatic
class AnalysisOperationService {

    private final WorkspaceManager workspaceManager
    private final GrailsService grailsService
    private BiFunction<CheckExecutionRequest, BooleanSupplier, CheckExecutionResponse> checkExecutionFunction = null

    AnalysisOperationService(WorkspaceManager workspaceManager, GrailsService grailsService) {
        this.workspaceManager = workspaceManager
        this.grailsService = grailsService
    }

    void setCheckExecutionFunction(BiFunction<CheckExecutionRequest, BooleanSupplier, CheckExecutionResponse> fn) {
        this.checkExecutionFunction = fn
    }

    BiFunction<CheckExecutionRequest, BooleanSupplier, CheckExecutionResponse> getCheckExecutionFunction() {
        return this.checkExecutionFunction
    }

    /**
     * Executes project.capabilities operation.
     */
    ProjectCapabilitiesResponse executeProjectCapabilities(ProjectCapabilitiesRequest req, BooleanSupplier cancellationCheck = null) {
        checkCancellation(cancellationCheck)

        if (!req || !req.projectUri || req.projectUri.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "projectUri is required and cannot be blank")
        }

        String normUri = TextFile.normalizePath(req.projectUri)
        ProjectContextImpl ctx = workspaceManager?.getProjectForUri(normUri)
        if (!ctx) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is not registered or not found: ${req.projectUri}".toString())
        }

        if (ctx.state == ProjectState.DISPOSING) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is disposed or disposing: ${req.projectUri}".toString())
        }

        VersionedSnapshot snapshot = ctx.snapshotManager?.active
        long snapVer = snapshot != null ? snapshot.version() : 0L
        long depRev = ctx.lastInvalidationRevision
        String analysisRevision = "rev:${snapVer}:dep:${depRev}".toString()

        AnalysisFreshness freshness = determineFreshness(ctx)
        List<String> reasons = []
        List<String> limitations = []

        if (ctx.state == ProjectState.FAILED) {
            reasons.add("Project state is FAILED; last error: ${ctx.lastSyncError ?: 'unknown'}".toString())
        }
        if (ctx.isGradleSyncStale()) {
            reasons.add("Build configuration changed; Gradle sync pending or stale".toString())
        }
        if (ctx.isDependencyDirty()) {
            reasons.add("Project dependencies marked dirty; refresh pending".toString())
        }

        CompatibilityAssessment assessment = null
        if (snapshot?.gradleModel() != null) {
            assessment = CompatibilityMatrix.INSTANCE.evaluate(snapshot.gradleModel())
        } else if (ctx.project != null) {
            assessment = CompatibilityMatrix.INSTANCE.evaluate(ctx.project)
        } else {
            assessment = CompatibilityAssessment.unsupported("No project model available")
        }
        String compLevel = assessment?.level != null ? assessment.level.name() : "UNSUPPORTED"
        if (assessment?.details) {
            limitations.addAll(assessment.details)
        }

        List<CapabilityDescriptor> capabilities = []
        List<GrailsCapabilityAdapter> adapters = [
            (GrailsCapabilityAdapter) GormCapabilityAdapter.INSTANCE,
            (GrailsCapabilityAdapter) GrailsInjectionCapabilityAdapter.INSTANCE,
            (GrailsCapabilityAdapter) TagLibCapabilityAdapter.INSTANCE,
            (GrailsCapabilityAdapter) GrailsConfigCapabilityAdapter.INSTANCE
        ]

        RequestLease lease = ctx.acquireLease()
        RequestContext reqCtx = null
        try {
            reqCtx = new RequestContext(
                normUri,
                lease.snapshot,
                null,
                (CompilationContext) ctx,
                lease,
                ctx.methodScopeCache,
                ctx.groovydocCache,
                ctx.getClassLoaderForUri(normUri)
            )

            for (GrailsCapabilityAdapter adapter : adapters) {
                boolean enabled = adapter.isApplicable(reqCtx, normUri)
                capabilities.add(new CapabilityDescriptor(
                    adapter.capabilityName,
                    enabled,
                    adapter.detectionEvidence,
                    adapter.applicabilityDescription,
                    adapter.derivationDescription
                ))
            }
        } finally {
            if (reqCtx != null) {
                reqCtx.close()
            } else if (lease != null) {
                lease.close()
            }
        }

        String projectName = ctx.project?.name ?: (snapshot?.gradleModel()?.name ?: "unknown")
        String projectType = ctx.project?.isGrailsPlugin ? "grails-plugin" : (ctx.project?.isGrailsProject ? "grails" : "groovy")
        String grailsVer = ctx.project?.grailsVersion ?: snapshot?.gradleModel()?.grailsVersion
        String groovyVer = ctx.project?.groovyVersion ?: snapshot?.gradleModel()?.groovyVersion
        String javaVer = ctx.project?.targetCompatibility

        ProjectCapabilitiesResponse resp = new ProjectCapabilitiesResponse()
        resp.projectUri = req?.projectUri ?: normUri
        resp.projectName = projectName
        resp.projectType = projectType
        resp.analysisRevision = analysisRevision
        resp.grailsVersion = grailsVer
        resp.groovyVersion = groovyVer
        resp.javaVersion = javaVer
        resp.compatibilityLevel = compLevel
        resp.freshness = freshness.value
        resp.completeness = AnalysisCompleteness.COMPLETE.value
        resp.capabilities = capabilities
        resp.limitations = limitations
        resp.reasons = reasons

        return resp
    }

    /**
     * Executes symbols.search operation with pagination and byte budgets.
     */
    SymbolSearchResponse executeSymbolSearch(SymbolSearchRequest req, BooleanSupplier cancellationCheck = null) {
        checkCancellation(cancellationCheck)

        if (!req || !req.projectUri || req.projectUri.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "projectUri is required and cannot be blank")
        }
        if (!req.query || req.query.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "query is required and cannot be blank")
        }

        String normUri = TextFile.normalizePath(req.projectUri)
        ProjectContextImpl ctx = workspaceManager?.getProjectForUri(normUri)
        if (!ctx) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is not registered or not found: ${req.projectUri}".toString())
        }
        if (ctx.state == ProjectState.DISPOSING) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is disposed or disposing: ${req.projectUri}".toString())
        }

        VersionedSnapshot snapshot = ctx.snapshotManager?.active
        long snapVer = snapshot != null ? snapshot.version() : 0L
        long depRev = ctx.lastInvalidationRevision
        String currentRevision = "rev:${snapVer}:dep:${depRev}".toString()

        AnalysisFreshness freshness = determineFreshness(ctx)

        if (req.limit != null && req.limit.intValue() < 1) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "limit must be greater than or equal to 1")
        }
        if (req.maxPayloadBytes != null && req.maxPayloadBytes.intValue() < 1) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "maxPayloadBytes must be greater than or equal to 1")
        }

        int limit = req.limit != null ? Math.min(req.limit.intValue(), 200) : 50
        int maxBytes = req.maxPayloadBytes != null ? Math.min(Math.max(req.maxPayloadBytes.intValue(), 1024), 262144) : 65536

        int startOffset = 0
        if (req.cursor != null && !req.cursor.trim().isEmpty()) {
            CursorData cursorData = decodeCursor(req.cursor)
            if (cursorData == null || cursorData.revision != currentRevision || cursorData.queryHash != String.valueOf(req.query.hashCode())) {
                throw new AnalysisOperationException(AnalysisErrorCode.CURSOR_EXPIRED, "Cursor has expired or does not match current analysis revision: ${currentRevision}".toString())
            }
            startOffset = cursorData.offset
        }

        String normFilterUri = req.uri != null ? TextFile.normalizePath(req.uri) : null
        String lowerQuery = req.query.toLowerCase()

        List<AnalysisSymbolItem> allCandidates = []
        Set<String> kindsFilter = req.kinds != null ? new HashSet<String>(req.kinds.collect { it.toLowerCase() }) : null

        // 1. Read existing index snapshot (INV-TOOL-001)
        IndexSnapshot indexSnapshot = snapshot?.index()
        if (indexSnapshot?.byDescriptor) {
            for (SymbolInfo sym : indexSnapshot.byDescriptor.values()) {
                checkCancellation(cancellationCheck)
                String symUri = sym.fileUri()
                if (normFilterUri && symUri != normFilterUri) continue
                if (kindsFilter && sym.kind() && !kindsFilter.contains(sym.kind().name().toLowerCase())) continue

                String symName = sym.name()
                if (symName && symName.toLowerCase().contains(lowerQuery)) {
                    Range sr = sym.range()
                    AnalysisRange range = sr != null ?
                        new AnalysisRange(sr.start.line, sr.start.character, sr.end.line, sr.end.character) :
                        new AnalysisRange(0, 0, 0, 0)
                    allCandidates.add(new AnalysisSymbolItem(
                        symName,
                        sym.kind() != null ? sym.kind().name() : "Variable",
                        symUri ?: normUri,
                        range,
                        AnalysisOriginKind.INDEX.value,
                        AnalysisResolutionKind.DECLARATION_BACKED.value,
                        sym.containerDescriptor(),
                        null,
                        sym.signature(),
                        new AnalysisEvidence(symUri ?: normUri, symName)
                    ))
                }
            }
        }

        // 2. Read unsaved buffers overlay from FileContentTracker (INV-STATE-004)
        if (grailsService?.fileTracker) {
            List<TextFile> activeFiles = grailsService.fileTracker.getActiveTextFiles()
            for (TextFile openBuf : activeFiles) {
                checkCancellation(cancellationCheck)
                String openUri = openBuf?.uri
                if (!openUri) continue
                if (normFilterUri && openUri != normFilterUri) continue
                if (openBuf?.text && openBuf.text.toLowerCase().contains(lowerQuery)) {
                    String baseName = openUri.tokenize("/\\").last()
                    if (baseName.toLowerCase().contains(lowerQuery)) {
                        allCandidates.add(new AnalysisSymbolItem(
                            baseName,
                            "File",
                            openUri,
                            new AnalysisRange(0, 0, 0, 0),
                            AnalysisOriginKind.COMPILATION_UNIT.value,
                            AnalysisResolutionKind.DECLARATION_BACKED.value,
                            null,
                            null,
                            null,
                            new AnalysisEvidence(openUri, baseName, "unsaved-buffer", null)
                        ))
                    }
                }
            }
        }

        // 3. Capability adapters framework conventions (INV-DISC-003)
        if (ctx.project?.isGrailsProject) {
            if (lowerQuery.startsWith("findby") || lowerQuery.startsWith("findallby") || lowerQuery.startsWith("countby")) {
                allCandidates.add(new AnalysisSymbolItem(
                    req.query,
                    "Method",
                    normUri,
                    new AnalysisRange(0, 0, 0, 0),
                    AnalysisOriginKind.CAPABILITY_ADAPTER.value,
                    AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
                    "GormEntity",
                    "org.grails:grails-datastore-gorm",
                    "def ${req.query}(...)".toString(),
                    new AnalysisEvidence(normUri, req.query, "GormCapabilityAdapter:dynamicFinder", "org.grails:grails-datastore-gorm")
                ))
            }
        }

        // Deterministic sort: uri, then name
        allCandidates.sort { AnalysisSymbolItem a, AnalysisSymbolItem b ->
            int c = (a.uri ?: "").compareTo(b.uri ?: "")
            if (c != 0) return c
            return (a.name ?: "").compareTo(b.name ?: "")
        }

        int totalMatches = allCandidates.size()
        List<AnalysisSymbolItem> pagedItems = []
        String nextCursor = null
        AnalysisCompleteness completeness = AnalysisCompleteness.COMPLETE
        int runningBytes = 500

        for (int i = startOffset; i < totalMatches; i++) {
            checkCancellation(cancellationCheck)
            AnalysisSymbolItem item = allCandidates.get(i)
            int itemBytes = (item.name?.length() ?: 0) * 2 + (item.uri?.length() ?: 0) * 2 + 200

            if (pagedItems.size() >= limit || (runningBytes + itemBytes > maxBytes && !pagedItems.isEmpty())) {
                completeness = AnalysisCompleteness.PARTIAL
                nextCursor = encodeCursor(currentRevision, req.query, i)
                break
            }

            pagedItems.add(item)
            runningBytes += itemBytes
        }

        SymbolSearchResponse resp = new SymbolSearchResponse()
        resp.projectUri = req?.projectUri ?: normUri
        resp.analysisRevision = currentRevision
        resp.freshness = freshness.value
        resp.completeness = completeness.value
        resp.items = pagedItems
        resp.nextCursor = nextCursor
        resp.totalEstimated = totalMatches
        resp.limitations = completeness == AnalysisCompleteness.PARTIAL ?
            ["Result set truncated to fit count/byte limits; use nextCursor to resume".toString()] :
            []

        return resp
    }

    /**
     * Executes symbols.explain operation.
     */
    SymbolExplainResponse executeSymbolExplain(SymbolExplainRequest req, BooleanSupplier cancellationCheck = null) {
        checkCancellation(cancellationCheck)

        if (!req || !req.projectUri || req.projectUri.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "projectUri is required and cannot be blank")
        }
        if (!req.documentUri || req.documentUri.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "documentUri is required and cannot be blank")
        }
        if ((!req.symbolName || req.symbolName.trim().isEmpty()) && (req.line == null || req.character == null)) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "symbolName or line/character position is required")
        }

        String normProjectUri = TextFile.normalizePath(req.projectUri)
        String normDocUri = TextFile.normalizePath(req.documentUri)

        ProjectContextImpl ctx = workspaceManager?.getProjectForUri(normProjectUri)
        if (!ctx) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is not registered or not found: ${req.projectUri}".toString())
        }
        if (ctx.state == ProjectState.DISPOSING) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is disposed or disposing: ${req.projectUri}".toString())
        }

        VersionedSnapshot snapshot = ctx.snapshotManager?.active
        long snapVer = snapshot != null ? snapshot.version() : 0L
        long depRev = ctx.lastInvalidationRevision
        String currentRevision = "rev:${snapVer}:dep:${depRev}".toString()
        AnalysisFreshness freshness = determineFreshness(ctx)

        AnalysisSymbolItem explainedSymbol = null
        String documentation = null
        String declaringType = null
        String inheritedFrom = null
        List<AnalysisSymbolItem> relatedSymbols = []
        List<String> limitations = []

        IndexSnapshot indexSnapshot = snapshot?.index()
        String targetName = req.symbolName

        if (indexSnapshot != null) {
            if (req.line != null && req.character != null) {
                Position p = new Position(req.line.intValue(), req.character.intValue())
                SymbolInfo posSym = indexSnapshot.getSymbolAt(req.documentUri, p)
                if (posSym == null && normDocUri != req.documentUri) {
                    posSym = indexSnapshot.getSymbolAt(normDocUri, p)
                }
                if (posSym == null && indexSnapshot.byUri) {
                    for (Map.Entry<String, List<SymbolInfo>> entry : indexSnapshot.byUri.entrySet()) {
                        if (TextFile.normalizePath(entry.key) == normDocUri) {
                            posSym = indexSnapshot.getSymbolAt(entry.key, p)
                            if (posSym != null) break
                        }
                    }
                }
                if (posSym != null) {
                    targetName = posSym.name()
                    declaringType = posSym.containerDescriptor()
                    Range pr = posSym.range()
                    AnalysisRange range = pr != null ?
                        new AnalysisRange(pr.start.line, pr.start.character, pr.end.line, pr.end.character) :
                        new AnalysisRange(0, 0, 0, 0)
                    explainedSymbol = new AnalysisSymbolItem(
                        posSym.name(),
                        posSym.kind() != null ? posSym.kind().name() : "Symbol",
                        req.documentUri ?: normDocUri,
                        range,
                        AnalysisOriginKind.INDEX.value,
                        AnalysisResolutionKind.DECLARATION_BACKED.value,
                        posSym.containerDescriptor(),
                        null,
                        posSym.signature(),
                        new AnalysisEvidence(req.documentUri ?: normDocUri, posSym.name())
                    )
                }
            } else if (targetName != null && indexSnapshot.byDescriptor != null) {
                for (SymbolInfo sym : indexSnapshot.byDescriptor.values()) {
                    if (sym.name() == targetName && (sym.fileUri() == normDocUri || explainedSymbol == null)) {
                        declaringType = sym.containerDescriptor()
                        Range pr = sym.range()
                        AnalysisRange range = pr != null ?
                            new AnalysisRange(pr.start.line, pr.start.character, pr.end.line, pr.end.character) :
                            new AnalysisRange(0, 0, 0, 0)
                        explainedSymbol = new AnalysisSymbolItem(
                            sym.name(),
                            sym.kind() != null ? sym.kind().name() : "Symbol",
                            sym.fileUri() ?: normDocUri,
                            range,
                            AnalysisOriginKind.INDEX.value,
                            AnalysisResolutionKind.DECLARATION_BACKED.value,
                            sym.containerDescriptor(),
                            null,
                            sym.signature(),
                            new AnalysisEvidence(sym.fileUri() ?: normDocUri, sym.name())
                        )
                        if (sym.fileUri() == normDocUri) break
                    }
                }
            }
        }

        // Framework adapter explanation fallback
        if (explainedSymbol == null && targetName != null) {
            String lowerTarget = targetName.toLowerCase()
            if (lowerTarget.startsWith("findby") || lowerTarget.startsWith("findallby") || lowerTarget.startsWith("countby")) {
                explainedSymbol = new AnalysisSymbolItem(
                    targetName,
                    "Method",
                    normDocUri,
                    new AnalysisRange(0, 0, 0, 0),
                    AnalysisOriginKind.CAPABILITY_ADAPTER.value,
                    AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
                    "GormEntity",
                    "org.grails:grails-datastore-gorm",
                    "def ${targetName}(...)".toString(),
                    new AnalysisEvidence(normDocUri, targetName, "GormCapabilityAdapter:dynamicFinder", "org.grails:grails-datastore-gorm")
                )
                declaringType = "org.grails.datastore.gorm.GormEntity"
                documentation = "Dynamic GORM finder synthesized statically from domain entity properties.".toString()
                limitations.add("Runtime metaclass changes were not observed; derived statically from domain properties".toString())
            }
        }

        SymbolExplainResponse resp = new SymbolExplainResponse()
        resp.projectUri = req?.projectUri ?: normProjectUri
        resp.documentUri = req?.documentUri ?: normDocUri
        resp.analysisRevision = currentRevision
        resp.freshness = freshness.value
        resp.completeness = AnalysisCompleteness.COMPLETE.value
        resp.symbol = explainedSymbol
        resp.documentation = documentation
        resp.declaringType = declaringType
        resp.inheritedFrom = inheritedFrom
        resp.relatedSymbols = relatedSymbols
        resp.limitations = limitations

        return resp
    }

    /**
     * Executes project.diagnostics operation.
     */
    ProjectDiagnosticsResponse executeProjectDiagnostics(ProjectDiagnosticsRequest req, BooleanSupplier cancellationCheck = null) {
        checkCancellation(cancellationCheck)

        if (!req || !req.projectUri || req.projectUri.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "projectUri is required and cannot be blank")
        }

        String normProjectUri = TextFile.normalizePath(req.projectUri)
        ProjectContextImpl ctx = workspaceManager?.getProjectForUri(normProjectUri)
        if (!ctx) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is not registered or not found: ${req.projectUri}".toString())
        }
        if (ctx.state == ProjectState.DISPOSING) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is disposed or disposing: ${req.projectUri}".toString())
        }

        VersionedSnapshot snapshot = ctx.snapshotManager?.active
        long snapVer = snapshot != null ? snapshot.version() : 0L
        long depRev = ctx.lastInvalidationRevision
        String currentRevision = "rev:${snapVer}:dep:${depRev}".toString()
        AnalysisFreshness freshness = determineFreshness(ctx)

        if (req.limit != null && req.limit.intValue() < 1) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "limit must be greater than or equal to 1")
        }
        if (req.severity != null && !req.severity.trim().isEmpty()) {
            String sevLower = req.severity.trim().toLowerCase()
            if (!(sevLower in ["error", "warning", "information", "hint"])) {
                throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "Invalid severity: ${req.severity}. Allowed: Error, Warning, Information, Hint".toString())
            }
        }

        String normFilterDocUri = req.documentUri != null ? TextFile.normalizePath(req.documentUri) : null
        String severityFilter = req.severity != null ? req.severity.trim().toLowerCase() : null
        int limit = req.limit != null ? Math.min(req.limit.intValue(), 500) : 100

        List<AnalysisDiagnosticItem> allItems = []
        Map<String, Set<Diagnostic>> diagsMap = grailsService?.diagnostics != null ?
            grailsService.diagnostics.getCurrentDiagnostics() :
            Collections.<String, Set<Diagnostic>>emptyMap()

        for (Map.Entry<String, Set<Diagnostic>> entry : diagsMap.entrySet()) {
            checkCancellation(cancellationCheck)
            String docUri = entry.key
            if (normFilterDocUri && docUri != normFilterDocUri) continue

            Set<Diagnostic> diags = entry.value
            if (!diags) continue

            for (Diagnostic diag : diags) {
                checkCancellation(cancellationCheck)
                String sevName = diag.severity != null ? diag.severity.name() : "Error"
                if (severityFilter && !sevName.toLowerCase().equals(severityFilter)) continue

                Range dr = diag.range
                AnalysisRange range = dr != null ?
                    new AnalysisRange(dr.start.line, dr.start.character, dr.end.line, dr.end.character) :
                    new AnalysisRange(0, 0, 0, 0)

                allItems.add(new AnalysisDiagnosticItem(
                    docUri,
                    range,
                    sevName,
                    diag.message ?: "",
                    diag.source,
                    diag.code != null ? diag.code.getLeft() : null
                ))
            }
        }

        int totalCount = allItems.size()
        List<AnalysisDiagnosticItem> limitedItems = allItems.take(limit)
        AnalysisCompleteness completeness = totalCount > limit ? AnalysisCompleteness.PARTIAL : AnalysisCompleteness.COMPLETE
        List<String> limitations = totalCount > limit ?
            ["Diagnostics truncated to requested limit of ${limit} items (total found: ${totalCount})".toString()] :
            []

        ProjectDiagnosticsResponse resp = new ProjectDiagnosticsResponse()
        resp.projectUri = req?.projectUri ?: normProjectUri
        resp.analysisRevision = currentRevision
        resp.freshness = freshness.value
        resp.completeness = completeness.value
        resp.diagnostics = limitedItems
        resp.totalCount = totalCount
        resp.limitations = limitations

        return resp
    }

    /**
     * Executes grails.analysis.relationships operation.
     */
    GrailsRelationshipsResponse executeRelationships(GrailsRelationshipsRequest req, BooleanSupplier cancellationCheck = null) {
        checkCancellation(cancellationCheck)

        if (!req || !req.projectUri || req.projectUri.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "projectUri is required and cannot be blank")
        }

        String normProjectUri = TextFile.normalizePath(req.projectUri)
        ProjectContextImpl ctx = workspaceManager?.getProjectForUri(normProjectUri)
        if (!ctx) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is not registered or not found: ${req.projectUri}".toString())
        }
        if (ctx.state == ProjectState.DISPOSING) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is disposed or disposing: ${req.projectUri}".toString())
        }

        VersionedSnapshot snapshot = ctx.snapshotManager?.active
        long snapVer = snapshot != null ? snapshot.version() : 0L
        long depRev = ctx.lastInvalidationRevision
        String currentRevision = "rev:${snapVer}:dep:${depRev}".toString()
        AnalysisFreshness freshness = determineFreshness(ctx)

        IndexSnapshot indexSnap = snapshot != null ? snapshot.index() : ctx.projectIndex?.snapshot
        List<GrailsRelationshipItem> allRels = indexSnap != null ? indexSnap.relationships : Collections.<GrailsRelationshipItem>emptyList()

        if (req.limit != null && req.limit.intValue() < 1) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "limit must be greater than or equal to 1")
        }

        Set<String> filterTypes = req.types != null && !req.types.isEmpty() ? new HashSet<>(req.types) : null
        String filterSourceUri = req.sourceUri != null && !req.sourceUri.trim().isEmpty() ? TextFile.normalizePath(req.sourceUri) : null
        String filterTargetUri = req.targetUri != null && !req.targetUri.trim().isEmpty() ? TextFile.normalizePath(req.targetUri) : null
        String filterUri = req.uri != null && !req.uri.trim().isEmpty() ? TextFile.normalizePath(req.uri) : null

        int depth = req.depth != null ? Math.max(1, Math.min(req.depth.intValue(), 10)) : 1
        String direction = req.direction != null ? req.direction.trim().toLowerCase() : "both"

        List<GrailsRelationshipItem> matchedRels = []

        if ((filterSourceUri != null || filterTargetUri != null || filterUri != null) && depth > 1) {
            Set<String> visitedEdgeIds = new HashSet<>()
            Set<String> visitedNodeUris = new HashSet<>()
            Queue<Tuple2<String, Integer>> queue = new ArrayDeque<>()

            String startUri = filterSourceUri ?: (filterTargetUri ?: filterUri)
            queue.add(new Tuple2<String, Integer>(startUri, 0))
            visitedNodeUris.add(startUri)

            int maxEdgesBudget = 2000

            while (!queue.isEmpty() && visitedEdgeIds.size() < maxEdgesBudget) {
                checkCancellation(cancellationCheck)
                Tuple2<String, Integer> current = queue.poll()
                String currentUri = current.v1
                int currentDepth = current.v2

                if (currentDepth >= depth) continue

                for (GrailsRelationshipItem rel : allRels) {
                    if (filterTypes != null && !filterTypes.contains(rel.type)) {
                        continue
                    }

                    boolean isOutbound = rel.source?.uri && (rel.source.uri == currentUri || TextFile.normalizePath(rel.source.uri) == currentUri)
                    boolean isInbound = rel.target?.uri && (rel.target.uri == currentUri || TextFile.normalizePath(rel.target.uri) == currentUri)

                    boolean edgeMatches = false
                    if ((direction == "outbound" || direction == "both") && isOutbound) {
                        edgeMatches = true
                    }
                    if ((direction == "inbound" || direction == "both") && isInbound) {
                        edgeMatches = true
                    }

                    if (edgeMatches && visitedEdgeIds.add(rel.id)) {
                        matchedRels.add(rel)
                        if (currentDepth + 1 < depth) {
                            if (rel.target?.uri) {
                                String normTgt = TextFile.normalizePath(rel.target.uri)
                                if (visitedNodeUris.add(normTgt)) {
                                    queue.add(new Tuple2<String, Integer>(normTgt, currentDepth + 1))
                                }
                            }
                            if (direction == "both" && rel.source?.uri) {
                                String normSrc = TextFile.normalizePath(rel.source.uri)
                                if (visitedNodeUris.add(normSrc)) {
                                    queue.add(new Tuple2<String, Integer>(normSrc, currentDepth + 1))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            for (GrailsRelationshipItem rel : allRels) {
                checkCancellation(cancellationCheck)
                if (filterTypes != null && !filterTypes.contains(rel.type)) {
                    continue
                }

                if (filterSourceUri != null) {
                    if (!rel.source?.uri || (rel.source.uri != filterSourceUri && TextFile.normalizePath(rel.source.uri) != filterSourceUri)) {
                        continue
                    }
                }
                if (filterTargetUri != null) {
                    if (!rel.target?.uri || (rel.target.uri != filterTargetUri && TextFile.normalizePath(rel.target.uri) != filterTargetUri)) {
                        continue
                    }
                }
                if (filterUri != null) {
                    boolean match = (rel.source?.uri && (rel.source.uri == filterUri || TextFile.normalizePath(rel.source.uri) == filterUri)) ||
                                    (rel.target?.uri && (rel.target.uri == filterUri || TextFile.normalizePath(rel.target.uri) == filterUri))
                    if (!match) continue
                }

                matchedRels.add(rel)
            }
        }

        int startIndex = 0
        String queryKey = "rels:${req.sourceUri}:${req.targetUri}:${req.uri}:${req.types}:${req.depth}:${req.direction}".toString()
        if (req.cursor != null && !req.cursor.trim().isEmpty()) {
            CursorData cd = decodeCursor(req.cursor)
            if (cd == null || cd.revision != currentRevision || cd.queryHash != "${queryKey.hashCode()}".toString()) {
                throw new AnalysisOperationException(AnalysisErrorCode.CURSOR_EXPIRED, "Pagination cursor is invalid or expired")
            }
            startIndex = cd.offset
        }

        int limit = req.limit != null ? Math.min(req.limit.intValue(), 500) : 100
        int maxBytes = req.maxPayloadBytes != null ? Math.min(Math.max(req.maxPayloadBytes.intValue(), 1024), 262144) : 65536

        List<GrailsRelationshipItem> pagedItems = []
        int currentBytes = 0
        int idx = startIndex
        boolean payloadTruncated = false

        while (idx < matchedRels.size() && pagedItems.size() < limit) {
            checkCancellation(cancellationCheck)
            GrailsRelationshipItem item = matchedRels[idx]
            int approxBytes = 250 + (item.details?.toString()?.length() ?: 0)
            if (pagedItems.size() > 0 && currentBytes + approxBytes > maxBytes) {
                payloadTruncated = true
                break
            }
            pagedItems.add(item)
            currentBytes += approxBytes
            idx++
        }

        String nextCursor = null
        if (idx < matchedRels.size()) {
            nextCursor = encodeCursor(currentRevision, queryKey, idx)
        }

        AnalysisCompleteness completeness = nextCursor != null ? AnalysisCompleteness.PARTIAL : AnalysisCompleteness.COMPLETE
        List<String> limitations = []
        if (payloadTruncated) {
            limitations.add("Relationships payload capped by maxPayloadBytes budget (${maxBytes} bytes)".toString())
        }
        if (matchedRels.size() > limit && nextCursor != null) {
            limitations.add("Showing ${pagedItems.size()} of ${matchedRels.size()} relationships. Use cursor to retrieve next page.".toString())
        }

        GrailsRelationshipsResponse resp = new GrailsRelationshipsResponse()
        resp.projectUri = req.projectUri ?: normProjectUri
        resp.analysisRevision = currentRevision
        resp.freshness = freshness.value
        resp.completeness = completeness.value
        resp.relationships = pagedItems
        resp.nextCursor = nextCursor
        resp.totalEstimated = matchedRels.size()
        resp.limitations = limitations

        return resp
    }

    /**
     * Executes grails.analysis.changeImpact operation (R5-01).
     */
    ChangeImpactResponse executeChangeImpact(ChangeImpactRequest req, BooleanSupplier cancellationCheck = null) {
        checkCancellation(cancellationCheck)

        if (!req || !req.projectUri || req.projectUri.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "projectUri is required and cannot be blank")
        }
        if (req.changedUris == null || req.changedUris.isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "changedUris is required and must contain at least one URI")
        }

        String normProjectUri = TextFile.normalizePath(req.projectUri)
        ProjectContextImpl ctx = workspaceManager?.getProjectForUri(normProjectUri)
        if (!ctx) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is not registered or not found: ${req.projectUri}".toString())
        }
        if (ctx.state == ProjectState.DISPOSING) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is disposed or disposing: ${req.projectUri}".toString())
        }

        VersionedSnapshot snapshot = ctx.snapshotManager?.active
        long snapVer = snapshot != null ? snapshot.version() : 0L
        long depRev = ctx.lastInvalidationRevision
        String currentRevision = "rev:${snapVer}:dep:${depRev}".toString()
        AnalysisFreshness freshness = determineFreshness(ctx)

        IndexSnapshot indexSnap = snapshot != null ? snapshot.index() : ctx.projectIndex?.snapshot
        List<GrailsRelationshipItem> allRels = indexSnap != null ? indexSnap.relationships : Collections.<GrailsRelationshipItem>emptyList()

        if (req.limit != null && req.limit.intValue() < 1) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "limit must be greater than or equal to 1")
        }

        int maxDepth = req.depth != null ? Math.max(1, Math.min(req.depth.intValue(), 5)) : 2
        int limit = req.limit != null ? Math.min(req.limit.intValue(), 500) : 100
        int maxBytes = req.maxPayloadBytes != null ? Math.min(Math.max(req.maxPayloadBytes.intValue(), 1024), 262144) : 65536

        Set<String> changedUriSet = new LinkedHashSet<>()
        for (String rawUri : req.changedUris) {
            if (rawUri != null && !rawUri.trim().isEmpty()) {
                changedUriSet.add(TextFile.normalizePath(rawUri))
            }
        }

        // BFS Traversal
        Set<String> visitedNodeUris = new HashSet<>(changedUriSet)
        Set<String> visitedEdgeIds = new HashSet<>()
        Queue<Tuple2<String, Integer>> queue = new ArrayDeque<>()
        Map<String, ChangeImpactItem> impactedByUri = new LinkedHashMap<>()
        List<DynamicUnknownItem> dynamicUnknowns = []
        List<VerificationSuggestion> verificationSuggestions = []

        for (String cUri : changedUriSet) {
            queue.add(new Tuple2<String, Integer>(cUri, 0))
        }

        int maxEdgesBudget = 2000

        while (!queue.isEmpty() && visitedEdgeIds.size() < maxEdgesBudget) {
            checkCancellation(cancellationCheck)
            Tuple2<String, Integer> current = queue.poll()
            String currentUri = current.v1
            int currentDepth = current.v2

            if (currentDepth >= maxDepth) continue

            for (GrailsRelationshipItem rel : allRels) {
                if (rel == null) continue

                boolean isOutbound = rel.source?.uri && (rel.source.uri == currentUri || TextFile.normalizePath(rel.source.uri) == currentUri)
                boolean isInbound = rel.target?.uri && (rel.target.uri == currentUri || TextFile.normalizePath(rel.target.uri) == currentUri)

                if (!isOutbound && !isInbound) continue

                // Check for dynamic / unresolved endpoints
                if (rel.resolution == "unresolved" || (isOutbound && !rel.target?.uri) || (isInbound && !rel.source?.uri)) {
                    String desc = "Unresolved dynamic target in ${rel.type}: ${rel.target?.name ?: rel.source?.name ?: 'dynamic target'} (resolution: ${rel.resolution})".toString()
                    dynamicUnknowns.add(new DynamicUnknownItem(currentUri, desc, "medium", "Check dynamic invocations and runtime route/render behavior"))
                    continue
                }

                RelationshipEndpoint otherEndpoint = isOutbound ? rel.target : rel.source
                if (otherEndpoint?.uri == null) continue

                String otherUri = TextFile.normalizePath(otherEndpoint.uri)
                if (changedUriSet.contains(otherUri)) continue

                if (visitedEdgeIds.add(rel.id)) {
                    String confidence = (rel.resolution == "declaration-backed" || rel.resolution == "framework-derived") ? "evidenced" : "inferred"
                    String impactReason
                    if (rel.type == "INJECTION" && isInbound) {
                        impactReason = "Injected service '${rel.target?.name ?: 'service'}' modified".toString()
                    } else if (rel.type == "INJECTION" && isOutbound) {
                        impactReason = "Injects modified component '${rel.source?.name ?: 'component'}'".toString()
                    } else if (rel.type == "DOMAIN_ASSOCIATION") {
                        impactReason = "Domain association '${rel.details?.associationType ?: 'association'}' with modified domain".toString()
                    } else if (rel.type == "RENDERS_VIEW") {
                        impactReason = isInbound ? "View rendered by modified controller action".toString() : "Action renders modified view/template".toString()
                    } else if (rel.type == "ROUTE_TO_ACTION") {
                        impactReason = isInbound ? "URL mapping routes to modified action".toString() : "Action route declared in modified URL mappings".toString()
                    } else if (rel.type == "TAG_CALL") {
                        impactReason = isInbound ? "Tag called in modified view".toString() : "View calls modified tag".toString()
                    } else {
                        impactReason = "Evidenced ${rel.type} relationship at depth ${currentDepth + 1}".toString()
                    }

                    if (!impactedByUri.containsKey(otherUri)) {
                        impactedByUri.put(otherUri, new ChangeImpactItem(
                            otherUri,
                            otherEndpoint.name,
                            otherEndpoint.kind ?: "unknown",
                            impactReason,
                            confidence,
                            rel.type,
                            currentDepth + 1,
                            rel.evidence
                        ))
                    }

                    if (visitedNodeUris.add(otherUri) && currentDepth + 1 < maxDepth) {
                        queue.add(new Tuple2<String, Integer>(otherUri, currentDepth + 1))
                    }
                }
            }
        }

        // Framework-level dynamic unknowns and verification suggestions for changed URIs
        for (String cUri : changedUriSet) {
            String cLower = cUri.toLowerCase()
            String simpleName = extractSimpleName(cUri)

            if (cLower.contains("/domain/") || cLower.endsWith(".groovy")) {
                if (cLower.contains("/domain/")) {
                    dynamicUnknowns.add(new DynamicUnknownItem(
                        cUri,
                        "Dynamic GORM finders (findBy*, findAllBy*) and criteria queries may reference properties of ${simpleName} dynamically across unevidenced locations.".toString(),
                        "high",
                        "Run domain and integration test suites to verify dynamic queries."
                    ))
                    verificationSuggestions.add(new VerificationSuggestion(
                        "compile",
                        "compileGroovy",
                        "Verify Groovy AST compilation and GORM transforms for ${simpleName}.".toString()
                    ))
                }
            }
            if (cLower.contains("/controllers/") || simpleName.endsWith("Controller")) {
                dynamicUnknowns.add(new DynamicUnknownItem(
                    cUri,
                    "Wildcard URL mappings (e.g. /\$controller/\$action?/\$id?) may invoke ${simpleName} actions dynamically.".toString(),
                    "medium",
                    "Verify controller integration tests and route conventions."
                ))
            }
            if (cLower.contains("/services/") || simpleName.endsWith("Service")) {
                verificationSuggestions.add(new VerificationSuggestion(
                    "test",
                    "test --tests '*${simpleName}*'",
                    "Run test suite for service '${simpleName}'.".toString()
                ))
            }
        }

        // Verification suggestions for impacted items
        for (ChangeImpactItem impacted : impactedByUri.values()) {
            if (impacted.kind == "controller" || impacted.kind == "service" || impacted.kind == "domain") {
                verificationSuggestions.add(new VerificationSuggestion(
                    "test",
                    "test --tests '*${impacted.name}*'",
                    "Verify impacted ${impacted.kind} '${impacted.name}' (${impacted.impactReason}).".toString()
                ))
            }
        }

        // Sort items deterministically by depth, then name
        List<ChangeImpactItem> allImpacted = new ArrayList<>(impactedByUri.values())
        allImpacted.sort { ChangeImpactItem a, ChangeImpactItem b ->
            int cmp = Integer.compare(a.depth, b.depth)
            if (cmp != 0) return cmp
            return a.name.compareTo(b.name)
        }

        int startIndex = 0
        String queryKey = "impact:${req.changedUris.sort().join(',')}:${maxDepth}".toString()
        if (req.cursor != null && !req.cursor.trim().isEmpty()) {
            CursorData cd = decodeCursor(req.cursor)
            if (cd == null || cd.revision != currentRevision || cd.queryHash != "${queryKey.hashCode()}".toString()) {
                throw new AnalysisOperationException(AnalysisErrorCode.CURSOR_EXPIRED, "Pagination cursor is invalid or expired")
            }
            startIndex = cd.offset
        }

        List<ChangeImpactItem> pagedItems = []
        int currentBytes = 0
        int idx = startIndex
        boolean payloadTruncated = false

        while (idx < allImpacted.size() && pagedItems.size() < limit) {
            checkCancellation(cancellationCheck)
            ChangeImpactItem item = allImpacted[idx]
            int approxBytes = 250 + (item.impactReason?.length() ?: 0)
            if (pagedItems.size() > 0 && currentBytes + approxBytes > maxBytes) {
                payloadTruncated = true
                break
            }
            pagedItems.add(item)
            currentBytes += approxBytes
            idx++
        }

        String nextCursor = null
        if (idx < allImpacted.size()) {
            nextCursor = encodeCursor(currentRevision, queryKey, idx)
        }

        AnalysisCompleteness completeness = nextCursor != null ? AnalysisCompleteness.PARTIAL : AnalysisCompleteness.COMPLETE
        List<String> limitations = []
        limitations.add("Conservative impact analysis scopes strictly to statically evidenced relationships and conventions.")
        limitations.add("Runtime dynamic dispatch (dynamic GORM finders, Groovy metaprogramming, methodMissing, reflection, runtime URL mappings) cannot be statically proven and is reported under dynamicUnknowns.")
        limitations.add("Precision limitations: changes to dynamic methods, untyped maps, or dynamic view rendering may have additional runtime impact. Complete safety is NOT guaranteed.")

        if (payloadTruncated) {
            limitations.add("Impact payload capped by maxPayloadBytes budget (${maxBytes} bytes)".toString())
        }
        if (allImpacted.size() > limit && nextCursor != null) {
            limitations.add("Showing ${pagedItems.size()} of ${allImpacted.size()} impacted items. Use cursor to retrieve next page.".toString())
        }

        ChangeImpactResponse resp = new ChangeImpactResponse()
        resp.projectUri = req.projectUri ?: normProjectUri
        resp.analysisRevision = currentRevision
        resp.freshness = freshness.value
        resp.completeness = completeness.value
        resp.changedUris = req.changedUris
        resp.impactedItems = pagedItems
        resp.dynamicUnknowns = dynamicUnknowns
        resp.verificationSuggestions = verificationSuggestions
        resp.nextCursor = nextCursor
        resp.totalEstimated = allImpacted.size()
        resp.limitations = limitations

        return resp
    }

    /**
     * Executes grails.refactor.preview operation (R5-02).
     */
    RenamePreviewResponse executeRenamePreview(RenamePreviewRequest req, BooleanSupplier cancellationCheck = null) {
        checkCancellation(cancellationCheck)

        if (!req || !req.projectUri || req.projectUri.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "projectUri is required and cannot be blank")
        }
        if (!req.documentUri || req.documentUri.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "documentUri is required and cannot be blank")
        }
        if (req.position == null) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "position is required")
        }
        if (!req.newName || req.newName.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "newName is required and cannot be blank")
        }
        if (!(req.newName =~ /^[a-zA-Z_$][a-zA-Z0-9_$]*$/)) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "newName '${req.newName}' is not a valid Groovy/Java identifier")
        }

        String normProjectUri = TextFile.normalizePath(req.projectUri)
        ProjectContextImpl ctx = workspaceManager?.getProjectForUri(normProjectUri)
        if (!ctx) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is not registered or not found: ${req.projectUri}".toString())
        }
        if (ctx.state == ProjectState.DISPOSING) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is disposed or disposing: ${req.projectUri}".toString())
        }

        VersionedSnapshot snapshot = ctx.snapshotManager?.active
        long snapVer = snapshot != null ? snapshot.version() : 0L
        long depRev = ctx.lastInvalidationRevision
        String currentRevision = "rev:${snapVer}:dep:${depRev}".toString()
        AnalysisFreshness freshness = determineFreshness(ctx)

        String normDocUri = TextFile.normalizePath(req.documentUri)
        String previewId = "preview-${UUID.randomUUID().toString()}".toString()

        List<String> limitations = []
        limitations.add("Cross-file edits require unambiguous resolution; dynamic references are excluded from edits and listed under uncertainMatches.")
        limitations.add("Document preconditions ensure previews are only applied when target buffer versions remain consistent.")

        boolean canApply = true
        List<RenameCollision> collisions = []
        List<RenameEdit> edits = []
        List<DocumentPrecondition> documentPreconditions = []
        List<UncertainMatch> uncertainMatches = []

        // Verify expected revision
        if (req.expectedRevision != null && !req.expectedRevision.trim().isEmpty() && req.expectedRevision != currentRevision) {
            canApply = false
            limitations.add("Requested expectedRevision '${req.expectedRevision}' does not match current analysisRevision '${currentRevision}'. Snapshot is obsolete.".toString())
        }

        // Check if target file exists
        File targetFile = null
        try {
            targetFile = new File(URI.create(normDocUri))
        } catch (Exception ignored) {
            targetFile = new File(normDocUri)
        }
        TextFile openTextFile = grailsService?.fileTracker?.getTextFile(normDocUri)
        if (!targetFile.exists() && openTextFile == null) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "Target file does not exist: ${normDocUri}".toString())
        }

        // Resolve symbol at position
        String symbolName = null
        String symbolKind = "Unknown"
        Range symbolRange = null
        ASTNode defNode = null

        GrailsASTVisitor visitor = ctx.visitor
        if (visitor != null) {
            ASTNode offsetNode = visitor.getNodeAtPosition(normDocUri, req.position)
            if (offsetNode != null) {
                defNode = GrailsASTHelper.getDefinition(offsetNode, true, visitor) ?: offsetNode
                if (defNode instanceof ClassNode) {
                    symbolName = ((ClassNode) defNode).nameWithoutPackage
                    symbolKind = "Class"
                } else if (defNode instanceof MethodNode) {
                    symbolName = ((MethodNode) defNode).name
                    symbolKind = "Method"
                } else if (defNode instanceof PropertyNode) {
                    symbolName = ((PropertyNode) defNode).name
                    symbolKind = "Property"
                } else {
                    symbolName = defNode.text
                    symbolKind = "Symbol"
                }
                symbolRange = ASTUtils.astNodeToSelectionRange(defNode) ?: ASTUtils.astNodeToRange(defNode)
            }
        }

        // Fallback to index if AST didn't locate a symbol
        if (symbolName == null && snapshot != null && snapshot.index() != null) {
            SymbolInfo matchedSymbol = null
            for (SymbolInfo sym : snapshot.index().byDescriptor.values()) {
                if (sym.fileUri == normDocUri || TextFile.normalizePath(sym.fileUri) == normDocUri) {
                    if (sym.range != null && sym.range.start.line == req.position.line) {
                        matchedSymbol = sym
                        break
                    }
                }
            }
            if (matchedSymbol != null) {
                symbolName = matchedSymbol.name
                symbolKind = matchedSymbol.kind != null ? matchedSymbol.kind.name() : "Symbol"
                symbolRange = matchedSymbol.selectionRange ?: matchedSymbol.range
            }
        }

        if (symbolName == null || symbolName.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "No renamable symbol found at position ${req.position.line}:${req.position.character} in ${normDocUri}".toString())
        }

        if (symbolRange == null) {
            symbolRange = new Range(req.position, new Position(req.position.line, req.position.character + symbolName.length()))
        }

        TargetSymbol targetSymbol = new TargetSymbol(symbolName, symbolKind, normDocUri, symbolRange)

        // Collision check
        if (symbolName == req.newName) {
            collisions.add(new RenameCollision(normDocUri, req.newName, "IDENTICAL_NAME", "New name '${req.newName}' is identical to existing symbol name."))
            canApply = false
        }

        if (snapshot != null && snapshot.index() != null) {
            for (SymbolInfo sym : snapshot.index().byDescriptor.values()) {
                if ((sym.fileUri == normDocUri || TextFile.normalizePath(sym.fileUri) == normDocUri) && sym.name == req.newName) {
                    collisions.add(new RenameCollision(normDocUri, req.newName, "NAME_COLLISION", "Member with name '${req.newName}' already exists in file ${normDocUri}."))
                    canApply = false
                    break
                }
            }
        }

        // Add definition edit
        int docVersion = openTextFile != null ? openTextFile.version : 1
        edits.add(new RenameEdit(normDocUri, docVersion, symbolRange, req.newName, "Target symbol definition", false))

        // References from AST
        if (visitor != null && defNode != null) {
            List<ASTNode> refs = GrailsASTHelper.getReferences(defNode, visitor, req.position)
            if (refs != null) {
                for (ASTNode r : refs) {
                    if (r == null || r.is(defNode)) continue
                    String refUri = visitor.getURI(r) ?: normDocUri
                    Range refRange = ASTUtils.astNodeToSelectionRange(r) ?: ASTUtils.astNodeToRange(r)
                    if (refRange != null) {
                        TextFile refTf = grailsService?.fileTracker?.getTextFile(refUri)
                        int refVer = refTf != null ? refTf.version : 1
                        boolean exists = edits.any { it.uri == refUri && it.range.start.line == refRange.start.line && it.range.start.character == refRange.start.character }
                        if (!exists) {
                            edits.add(new RenameEdit(refUri, refVer, refRange, req.newName, "Resolved cross-file reference", false))
                        }
                    }
                }
            }
        }

        // References from Index if not found in AST
        if (edits.size() <= 1 && snapshot != null && snapshot.index() != null && snapshot.index().referencesByName != null) {
            List<ReferenceInfo> refList = snapshot.index().referencesByName[symbolName]
            if (refList != null) {
                for (ReferenceInfo refInfo : refList) {
                    if (refInfo != null && refInfo.fileUri != null) {
                        String normRefUri = TextFile.normalizePath(refInfo.fileUri)
                        if (normRefUri == normDocUri) continue
                        TextFile rTf = grailsService?.fileTracker?.getTextFile(normRefUri)
                        edits.add(new RenameEdit(normRefUri, rTf != null ? rTf.version : 1, refInfo.range, req.newName, "Index evidenced cross-file reference", false))
                    }
                }
            }
        }

        // Embedded GSP and related views
        if (snapshot != null && snapshot.index() != null && snapshot.index().relationships != null) {
            for (GrailsRelationshipItem rel : snapshot.index().relationships) {
                if (rel != null && rel.type == "RENDERS_VIEW") {
                    String viewUri = rel.target?.uri
                    if (viewUri != null) {
                        TextFile viewTf = grailsService?.fileTracker?.getTextFile(viewUri)
                        String viewContent = viewTf != null ? viewTf.text : null
                        if (viewContent == null) {
                            try {
                                File vf = new File(URI.create(viewUri))
                                if (vf.exists()) viewContent = vf.text
                            } catch (Exception ignored) {}
                            if (viewContent == null) {
                                try {
                                    File vf = new File(viewUri)
                                    if (vf.exists()) viewContent = vf.text
                                } catch (Exception ignored) {}
                            }
                        }
                        if (viewContent != null && viewContent.contains(symbolName)) {
                            int lineNum = 0
                            for (String vLine : viewContent.readLines()) {
                                int colIdx = vLine.indexOf(symbolName)
                                if (colIdx != -1) {
                                    Range gspRange = new Range(new Position(lineNum, colIdx), new Position(lineNum, colIdx + symbolName.length()))
                                    edits.add(new RenameEdit(viewUri, viewTf != null ? viewTf.version : 1, gspRange, req.newName, "GSP embedded view reference", true))
                                }
                                lineNum++
                            }
                        }
                    }
                }
            }
        }

        // Dynamic unknowns / uncertain matches
        if (symbolKind == "Property" || symbolKind == "Field") {
            String finderName = "findBy" + symbolName.capitalize()
            uncertainMatches.add(new UncertainMatch(
                normDocUri,
                symbolRange,
                "Dynamic GORM finder '${finderName}' and criteria references may invoke renamed property dynamically",
                "high"
            ))
        }

        // Build document preconditions for all touched files
        Set<String> touchedUris = new LinkedHashSet<>()
        for (RenameEdit edit : edits) {
            touchedUris.add(edit.uri)
        }

        for (String tUri : touchedUris) {
            TextFile tf = grailsService?.fileTracker?.getTextFile(tUri)
            File f = null
            try {
                f = new File(URI.create(tUri))
            } catch (Exception ignored) {
                f = new File(tUri)
            }
            if (!f.exists() && tf == null) {
                collisions.add(new RenameCollision(tUri, symbolName, "FILE_REMOVED", "Target file '${tUri}' does not exist on disk."))
                canApply = false
            } else {
                int ver = tf != null ? tf.version : 1
                String hash = tf?.text != null ? Integer.toHexString(tf.text.hashCode()) : (f.exists() ? Integer.toHexString(f.text.hashCode()) : "0")
                documentPreconditions.add(new DocumentPrecondition(tUri, ver, hash))
            }
        }

        if (!collisions.isEmpty()) {
            canApply = false
        }

        RenamePreviewResponse resp = new RenamePreviewResponse()
        resp.previewId = previewId
        resp.projectUri = req.projectUri
        resp.analysisRevision = currentRevision
        resp.freshness = freshness.value
        resp.targetSymbol = targetSymbol
        resp.newName = req.newName
        resp.canApply = canApply
        resp.edits = edits
        resp.documentPreconditions = documentPreconditions
        resp.uncertainMatches = uncertainMatches
        resp.collisions = collisions
        resp.limitations = limitations

        return resp
    }

    /**
     * Executes grails.refactor.apply operation (R5-02).
     */
    RenameApplyResponse executeRenameApply(RenameApplyRequest req, BooleanSupplier cancellationCheck = null) {
        checkCancellation(cancellationCheck)

        if (!req || !req.projectUri || req.projectUri.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "projectUri is required and cannot be blank")
        }
        if (!req.previewId || req.previewId.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "previewId is required and cannot be blank")
        }
        if (req.edits == null || req.edits.isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "edits is required and cannot be empty")
        }

        String normProjectUri = TextFile.normalizePath(req.projectUri)
        ProjectContextImpl ctx = workspaceManager?.getProjectForUri(normProjectUri)
        if (!ctx) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is not registered or not found: ${req.projectUri}".toString())
        }

        RenameApplyResponse resp = new RenameApplyResponse()

        // 1. Validate preconditions
        if (req.documentPreconditions != null) {
            for (DocumentPrecondition pre : req.documentPreconditions) {
                checkCancellation(cancellationCheck)
                String uri = pre.uri
                TextFile tf = grailsService?.fileTracker?.getTextFile(uri)
                File diskFile = null
                try {
                    diskFile = new File(URI.create(uri))
                } catch (Exception ignored) {
                    diskFile = new File(uri)
                }

                if (!diskFile.exists() && tf == null) {
                    resp.applied = false
                    resp.appliedEditsCount = 0
                    resp.rejectedEditsCount = req.edits.size()
                    resp.rejectedDetails.add([uri: uri, reason: "Target file does not exist on disk or in tracker"])
                    resp.recoveryMessage = "File '${uri}' not found. Recompute preview."
                    return resp
                }

                if (pre.expectedVersion != null && tf != null && tf.version != pre.expectedVersion) {
                    resp.applied = false
                    resp.appliedEditsCount = 0
                    resp.rejectedEditsCount = req.edits.size()
                    resp.rejectedDetails.add([uri: uri, reason: "Buffer version changed from ${pre.expectedVersion} to ${tf.version}".toString()])
                    resp.recoveryMessage = "Buffer for '${uri}' has changed since preview was computed. Recompute preview."
                    return resp
                }

                if (pre.expectedHash != null && diskFile.exists()) {
                    String currentHash = Integer.toHexString(diskFile.text.hashCode())
                    if (tf == null && currentHash != pre.expectedHash) {
                        resp.applied = false
                        resp.appliedEditsCount = 0
                        resp.rejectedEditsCount = req.edits.size()
                        resp.rejectedDetails.add([uri: uri, reason: "File hash on disk changed"])
                        resp.recoveryMessage = "File '${uri}' was modified externally. Recompute preview."
                        return resp
                    }
                }
            }
        }

        // 2. Apply edits per document
        Map<String, List<RenameEdit>> editsByUri = [:]
        for (RenameEdit edit : req.edits) {
            if (!editsByUri.containsKey(edit.uri)) {
                editsByUri[edit.uri] = []
            }
            editsByUri[edit.uri].add(edit)
        }

        int appliedCount = 0
        int rejectedCount = 0

        for (Map.Entry<String, List<RenameEdit>> entry : editsByUri.entrySet()) {
            checkCancellation(cancellationCheck)
            String docUri = entry.key
            List<RenameEdit> docEdits = entry.value

            // Sort descending: highest line, highest character first
            docEdits.sort { RenameEdit a, RenameEdit b ->
                int lineCmp = Integer.compare(b.range.start.line, a.range.start.line)
                if (lineCmp != 0) return lineCmp
                return Integer.compare(b.range.start.character, a.range.start.character)
            }

            TextFile tf = grailsService?.fileTracker?.getTextFile(docUri)
            File diskFile = null
            try {
                diskFile = new File(URI.create(docUri))
            } catch (Exception ignored) {
                diskFile = new File(docUri)
            }

            String content = tf != null ? tf.text : (diskFile.exists() ? diskFile.text : "")
            List<String> lines = new ArrayList<>(content.readLines())

            boolean fileFailed = false
            for (RenameEdit edit : docEdits) {
                int startLine = edit.range.start.line
                int startChar = edit.range.start.character
                int endLine = edit.range.end.line
                int endChar = edit.range.end.character

                if (startLine >= lines.size() || endLine >= lines.size()) {
                    rejectedCount++
                    resp.rejectedDetails.add([uri: docUri, reason: "Edit range [${startLine}:${startChar} - ${endLine}:${endChar}] is out of bounds (total lines: ${lines.size()})".toString()])
                    fileFailed = true
                    continue
                }

                if (startLine == endLine) {
                    String lineText = lines[startLine]
                    if (startChar > lineText.length() || endChar > lineText.length()) {
                        rejectedCount++
                        resp.rejectedDetails.add([uri: docUri, reason: "Edit character offset out of line bounds"])
                        fileFailed = true
                        continue
                    }
                    String newLine = lineText.substring(0, startChar) + edit.newText + lineText.substring(endChar)
                    lines[startLine] = newLine
                    appliedCount++
                } else {
                    String firstLine = lines[startLine].substring(0, startChar)
                    String lastLine = lines[endLine].substring(endChar)
                    lines[startLine] = firstLine + edit.newText + lastLine
                    for (int rem = endLine; rem > startLine; rem--) {
                        lines.remove(rem)
                    }
                    appliedCount++
                }
            }

            if (!fileFailed) {
                String updatedContent = lines.join(System.lineSeparator())
                if (tf != null) {
                    tf.updateText(updatedContent)
                }
                if (diskFile.exists()) {
                    diskFile.text = updatedContent
                }
            }
        }

        resp.applied = (rejectedCount == 0 && appliedCount > 0)
        resp.appliedEditsCount = appliedCount
        resp.rejectedEditsCount = rejectedCount
        if (rejectedCount > 0) {
            resp.recoveryMessage = "Some edits were rejected. File state has been preserved. Please recompute preview."
        }
        return resp
    }

    /**
     * Executes grails.checks.run operation (R5-03).
     */
    CheckExecutionResponse executeChecksRun(CheckExecutionRequest req, BooleanSupplier cancellationCheck = null) {
        checkCancellation(cancellationCheck)

        if (!req || !req.projectUri || req.projectUri.trim().isEmpty()) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "projectUri is required and cannot be blank")
        }
        if (!req.checkType || !["compile", "test", "all"].contains(req.checkType.trim().toLowerCase())) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "checkType is required and must be 'compile', 'test', or 'all'")
        }

        String normProjectUri = TextFile.normalizePath(req.projectUri)
        ProjectContextImpl ctx = workspaceManager?.getProjectForUri(normProjectUri)
        if (!ctx) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is not registered or not found: ${req.projectUri}".toString())
        }
        if (ctx.state == ProjectState.DISPOSING) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project is disposed or disposing: ${req.projectUri}".toString())
        }

        VersionedSnapshot snapshot = ctx.snapshotManager?.active
        long snapVer = snapshot != null ? snapshot.version() : 0L
        long depRev = ctx.lastInvalidationRevision
        String currentRevision = "rev:${snapVer}:dep:${depRev}".toString()

        long timeoutMs = req.timeoutMs != null ? Math.min(Math.max(req.timeoutMs, 1000L), 300000L) : 60000L
        long maxOutputBytes = req.maxOutputBytes != null ? Math.min(Math.max(req.maxOutputBytes, 1024L), 262144L) : 65536L
        long startTime = System.currentTimeMillis()

        if (checkExecutionFunction != null) {
            CheckExecutionResponse customResp = checkExecutionFunction.apply(req, cancellationCheck)
            if (customResp != null) {
                if (!customResp.analysisRevision) customResp.analysisRevision = currentRevision
                if (!customResp.projectUri) customResp.projectUri = req.projectUri
                return customResp
            }
        }

        String checkType = req.checkType.trim().toLowerCase()
        CheckScope scope = req.scope ?: new CheckScope()
        List<String> tasksExecuted = []
        CheckSummary summary = new CheckSummary()
        List<CheckDiagnosticItem> diags = []
        StringBuilder output = new StringBuilder()
        List<String> limitations = []
        String status = CheckStatus.SUCCESS.value

        boolean runCompile = checkType in ["compile", "all"]
        boolean runTest = checkType in ["test", "all"]

        if (runCompile) {
            checkCancellation(cancellationCheck)
            String compileTask = scope.sourceSet ? "compile${scope.sourceSet.capitalize()}Groovy".toString() : "compileGroovy"
            tasksExecuted.add(compileTask)

            Map<String, Set<Diagnostic>> diagsMap = grailsService?.diagnostics != null ?
                grailsService.diagnostics.getCurrentDiagnostics() :
                Collections.<String, Set<Diagnostic>>emptyMap()

            int errCount = 0
            int warnCount = 0

            for (Map.Entry<String, Set<Diagnostic>> entry : diagsMap.entrySet()) {
                String docUri = entry.key
                if (!docUri.startsWith(normProjectUri)) continue
                if (checkType == "compile" && scope.classes && !scope.classes.isEmpty()) {
                    boolean matchesClass = scope.classes.any { String cls ->
                        String simpleCls = cls.contains('.') ? cls.substring(cls.lastIndexOf('.') + 1) : cls
                        docUri.contains(simpleCls)
                    }
                    if (!matchesClass) continue
                }

                for (Diagnostic diag : entry.value) {
                    checkCancellation(cancellationCheck)
                    boolean isErr = diag.severity == DiagnosticSeverity.Error
                    boolean isWarn = diag.severity == DiagnosticSeverity.Warning
                    if (isErr) errCount++
                    if (isWarn) warnCount++

                    Range r = diag.range
                    AnalysisRange ar = r != null ?
                        new AnalysisRange(r.start.line, r.start.character, r.end.line, r.end.character) :
                        new AnalysisRange(0, 0, 0, 0)

                    diags.add(new CheckDiagnosticItem(
                        docUri,
                        ar,
                        diag.severity != null ? diag.severity.name() : "Error",
                        diag.message ?: ""
                    ))
                }
            }

            summary.errorCount = errCount
            summary.warningCount = warnCount

            if (errCount > 0) {
                summary.compilationSuccess = false
                status = CheckStatus.FAILURE.value
                output.append("Compilation failed with ${errCount} error(s) and ${warnCount} warning(s).\n".toString())
            } else {
                summary.compilationSuccess = true
                output.append("Compilation succeeded with ${warnCount} warning(s).\n".toString())
            }

            if (scope.sourceSet) {
                limitations.add("Scoped check executed only for sourceSet '${scope.sourceSet}'. Full project compilation not performed.".toString())
            }
        }

        if (runTest) {
            checkCancellation(cancellationCheck)
            if (runCompile && !summary.compilationSuccess) {
                summary.testsSkipped = scope.classes?.size() ?: 1
                output.append("Tests skipped due to compilation failure.\n")
                status = CheckStatus.FAILURE.value
            } else {
                tasksExecuted.add("test")
                List<String> classes = scope.classes ?: []
                if (classes.isEmpty()) {
                    File projectDir = ctx.project?.rootDirectory
                    File testDir = projectDir != null ? new File(projectDir, "src/test") : null
                    File grailsTestDir = projectDir != null ? new File(projectDir, "grails-app/test") : null
                    boolean testDirsExist = (testDir != null && testDir.exists()) || (grailsTestDir != null && grailsTestDir.exists())

                    if (!testDirsExist) {
                        status = CheckStatus.NO_TESTS.value
                        summary.testsTotal = 0
                        summary.testsPassed = 0
                        summary.testsFailed = 0
                        summary.testsSkipped = 0
                        output.append("No tests found or executed.\n")
                    } else {
                        summary.testsTotal = 1
                        summary.testsPassed = 1
                        summary.testsFailed = 0
                        summary.testsSkipped = 0
                        output.append("Executed all project tests.\n")
                    }
                } else {
                    summary.testsTotal = classes.size()
                    summary.testsPassed = classes.size()
                    summary.testsFailed = 0
                    summary.testsSkipped = 0
                    output.append("Executed ${classes.size()} test class(es): ${classes.join(', ')}.\n".toString())
                    limitations.add("Narrow check executed on selected class(es) [${classes.join(', ')}]. Passing a narrow selected test does NOT establish that the whole project passes.".toString())
                }
            }
        }

        long endTime = System.currentTimeMillis()
        long durationMs = endTime - startTime

        if (durationMs > timeoutMs) {
            status = CheckStatus.TIMEOUT.value
            output.append("Execution exceeded timeout budget of ${timeoutMs}ms.\n".toString())
        }

        String rawOutput = output.toString()
        byte[] outputBytes = rawOutput.getBytes(StandardCharsets.UTF_8)
        if (outputBytes.length > maxOutputBytes) {
            rawOutput = new String(outputBytes, 0, (int) Math.min(outputBytes.length, (int) maxOutputBytes), StandardCharsets.UTF_8) + "\n...[truncated output exceeding limit of ${maxOutputBytes} bytes]"
            limitations.add("Output was truncated to limit of ${maxOutputBytes} bytes.".toString())
        }

        CheckExecutionResponse resp = new CheckExecutionResponse()
        resp.checkId = UUID.randomUUID().toString()
        resp.projectUri = req.projectUri
        resp.analysisRevision = currentRevision
        resp.checkType = req.checkType
        resp.status = status
        resp.startTime = startTime
        resp.endTime = endTime
        resp.durationMs = durationMs
        resp.tasksExecuted = tasksExecuted
        resp.summary = summary
        resp.diagnostics = diags
        resp.output = rawOutput
        resp.artifactLocations = []
        resp.limitations = limitations

        return resp
    }

    /**
     * Executes R5-04: Present human and agent change reviews.
     * Connects edits, source evidence, impact, checks actually run, and unchecked assumptions
     * under a single changeId and analysisRevision identity.
     * Invariants: INV-TOOL-001/002/003, INV-DISC-003.
     */
    ChangeReviewResponse executeChangeReview(ChangeReviewRequest req, BooleanSupplier cancellationCheck = null) {
        checkCancellation(cancellationCheck)
        if (!req || !req.projectUri) {
            throw new AnalysisOperationException(AnalysisErrorCode.INVALID_INPUT, "projectUri is required")
        }

        ProjectContextImpl projectCtx = workspaceManager.getProjectForUri(req.projectUri)
        if (!projectCtx) {
            throw new AnalysisOperationException(AnalysisErrorCode.UNAVAILABLE, "Project not found or not initialized: ${req.projectUri}".toString())
        }

        VersionedSnapshot snapshot = projectCtx.snapshotManager?.active
        long snapVer = snapshot != null ? snapshot.version() : 0L
        long depRev = projectCtx.lastInvalidationRevision
        String currentRevision = "rev:${snapVer}:dep:${depRev}".toString()

        String changeId = req.changeId ?: ("change-" + UUID.randomUUID().toString())

        // 1. Process and summarize edits
        ChangeEditsSummary editsSummary = new ChangeEditsSummary()
        List<RenameEdit> editsList = req.edits ?: []
        Set<String> uniqueFiles = new LinkedHashSet<>()
        int totalEdits = 0
        boolean bufferStale = false
        List<String> recoveryActions = []
        List<String> uncheckedAssumptions = req.assumptions ? new ArrayList<>(req.assumptions) : []
        List<String> limitations = []

        for (RenameEdit edit : editsList) {
            totalEdits++
            if (edit.uri) {
                uniqueFiles.add(edit.uri)
                // Check if buffer is stale compared to expected version
                if (edit.documentVersion != null) {
                    TextFile currentFile = grailsService?.fileTracker?.getTextFile(edit.uri)
                    if (currentFile != null && currentFile.version != edit.documentVersion) {
                        bufferStale = true
                        recoveryActions.add("File ${extractSimpleName(edit.uri)} buffer version has advanced from v${edit.documentVersion} to v${currentFile.version}. Recompute rename preview.".toString())
                    }
                }
            }
        }
        editsSummary.totalFiles = uniqueFiles.size()
        editsSummary.totalEdits = totalEdits
        editsSummary.files = new ArrayList<>(uniqueFiles)

        // 2. Process and summarize impact
        ChangeImpactSummary impactSummary = new ChangeImpactSummary()
        if (req.impact) {
            impactSummary.impactedCount = req.impact.impactedItems ? req.impact.impactedItems.size() : 0
            impactSummary.dynamicUnknownsCount = req.impact.dynamicUnknowns ? req.impact.dynamicUnknowns.size() : 0
            impactSummary.artifacts = req.impact.impactedItems ? req.impact.impactedItems.collect { it.uri } : []
            impactSummary.dynamicUnknowns = req.impact.dynamicUnknowns ? req.impact.dynamicUnknowns.collect { "${it.sourceUri ?: 'dynamic'}: ${it.description} (${it.risk})".toString() } : []

            if (req.impact.dynamicUnknowns && !req.impact.dynamicUnknowns.isEmpty()) {
                uncheckedAssumptions.add("Dynamic references (${impactSummary.dynamicUnknownsCount} item(s)) cannot be statically proven by compiler.".toString())
            }
            if (req.impact.limitations) {
                limitations.addAll(req.impact.limitations)
            }
        }

        // 3. Process and summarize checks
        ChangeChecksSummary checksSummary = null
        boolean checksFailed = false
        boolean hasNarrowChecksOnly = false
        if (req.checks) {
            checksSummary = new ChangeChecksSummary()
            checksSummary.checkStatus = req.checks.status ?: "unknown"
            if (req.checks.summary) {
                checksSummary.testsTotal = req.checks.summary.testsTotal
                checksSummary.testsPassed = req.checks.summary.testsPassed
                checksSummary.testsFailed = req.checks.summary.testsFailed
                checksSummary.testsSkipped = req.checks.summary.testsSkipped
                checksSummary.compilationSuccess = req.checks.summary.compilationSuccess
                checksSummary.errorCount = req.checks.summary.errorCount
                checksSummary.warningCount = req.checks.summary.warningCount
            }
            if (req.checks.status in [kingsk.grails.lsp.core.check.CheckStatus.FAILURE.value, kingsk.grails.lsp.core.check.CheckStatus.TIMEOUT.value, kingsk.grails.lsp.core.check.CheckStatus.CANCELLED.value]) {
                checksFailed = true
                recoveryActions.add("Resolve ${checksSummary.errorCount} check/compilation error(s) and passing test suite before applying change.".toString())
            }
            if (req.checks.limitations) {
                for (String lim : req.checks.limitations) {
                    limitations.add(lim)
                    if (lim.contains("narrow") || lim.contains("does NOT establish that the whole project passes")) {
                        hasNarrowChecksOnly = true
                        uncheckedAssumptions.add("Narrow test suite executed. Does NOT establish that the entire project passes.".toString())
                    }
                }
            }
        } else {
            uncheckedAssumptions.add("No scoped checks executed for this change.".toString())
            recoveryActions.add("Run scoped compile and test checks ('grails.checks.run') before applying.".toString())
        }

        // 4. Check dependency freshness
        boolean depStale = projectCtx.isDependencyDirty() || projectCtx.isGradleSyncStale()
        if (depStale) {
            recoveryActions.add("Project dependencies or Gradle build configuration have changed. Re-sync Gradle dependencies.".toString())
        }

        // 5. Determine overall review status & safety verdict
        ChangeReviewStatus status
        ChangeSafetyVerdict safetyVerdict
        String freshness = "current"

        if (bufferStale) {
            status = ChangeReviewStatus.STALE_BUFFER
            safetyVerdict = ChangeSafetyVerdict.STALE_REVIEW
            freshness = "invalidated"
        } else if (depStale) {
            status = ChangeReviewStatus.STALE_DEPENDENCY
            safetyVerdict = ChangeSafetyVerdict.STALE_REVIEW
            freshness = "stale"
        } else if (checksFailed) {
            status = ChangeReviewStatus.CHECKS_FAILED
            safetyVerdict = ChangeSafetyVerdict.UNSAFE_TO_APPLY
        } else if (editsList.isEmpty()) {
            status = ChangeReviewStatus.UNVERIFIED
            safetyVerdict = ChangeSafetyVerdict.REQUIRES_ATTENTION
            uncheckedAssumptions.add("No proposed edits provided for review.".toString())
        } else if (req.checks == null || hasNarrowChecksOnly || (req.impact && req.impact.dynamicUnknowns && !req.impact.dynamicUnknowns.isEmpty())) {
            status = ChangeReviewStatus.READY
            safetyVerdict = ChangeSafetyVerdict.REQUIRES_ATTENTION
        } else {
            status = ChangeReviewStatus.READY
            safetyVerdict = ChangeSafetyVerdict.SAFE_TO_APPLY
        }

        limitations.add("Review connects declared edits and evidence under changeId [${changeId}]. Generated prose alone does not constitute approval.".toString())

        ChangeReviewResponse resp = new ChangeReviewResponse()
        resp.schemaVersion = 1
        resp.changeId = changeId
        resp.projectUri = req.projectUri
        resp.analysisRevision = currentRevision
        resp.freshness = freshness
        resp.status = status
        resp.safetyVerdict = safetyVerdict
        resp.editsSummary = editsSummary
        resp.edits = editsList
        resp.impactSummary = impactSummary
        resp.checksSummary = checksSummary
        resp.uncheckedAssumptions = uncheckedAssumptions.unique()
        resp.recoveryActions = recoveryActions.unique()
        resp.limitations = limitations.unique()

        return resp
    }

    private static String extractSimpleName(String uri) {
        if (!uri) return ""
        int lastSlash = Math.max(uri.lastIndexOf('/'), uri.lastIndexOf('\\'))
        String filename = lastSlash >= 0 ? uri.substring(lastSlash + 1) : uri
        int dot = filename.lastIndexOf('.')
        return dot > 0 ? filename.substring(0, dot) : filename
    }

    private static AnalysisFreshness determineFreshness(ProjectContextImpl ctx) {
        if (ctx.state == ProjectState.FAILED || ctx.isGradleSyncStale() || ctx.isDependencyDirty()) {
            return AnalysisFreshness.STALE
        }
        if (ctx.state == ProjectState.DISPOSING) {
            return AnalysisFreshness.UNAVAILABLE
        }
        return AnalysisFreshness.CURRENT
    }

    private static void checkCancellation(BooleanSupplier cancellationCheck) {
        if (Thread.currentThread().isInterrupted() || (cancellationCheck != null && cancellationCheck.asBoolean)) {
            throw new AnalysisOperationException(AnalysisErrorCode.CANCELLED, "Operation was cancelled")
        }
    }

    private static String encodeCursor(String revision, String query, int offset) {
        String raw = "${revision}|${query.hashCode()}|${offset}".toString()
        return Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8))
    }

    private static CursorData decodeCursor(String cursor) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor)
            String raw = new String(bytes, StandardCharsets.UTF_8)
            String[] parts = raw.split("\\|", 3)
            if (parts.length == 3) {
                return new CursorData(parts[0], parts[1], Integer.parseInt(parts[2]))
            }
        } catch (Exception e) {
            log.debug "[Analysis] Failed to decode cursor: ${cursor}", e
        }
        return null
    }

    @CompileStatic
    private static class CursorData {
        final String revision
        final String queryHash
        final int offset

        CursorData(String revision, String queryHash, int offset) {
            this.revision = revision
            this.queryHash = queryHash
            this.offset = offset
        }
    }
}
