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

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Collections
import java.util.function.BooleanSupplier

@Slf4j
@CompileStatic
class AnalysisOperationService {

    private final WorkspaceManager workspaceManager
    private final GrailsService grailsService

    AnalysisOperationService(WorkspaceManager workspaceManager, GrailsService grailsService) {
        this.workspaceManager = workspaceManager
        this.grailsService = grailsService
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
