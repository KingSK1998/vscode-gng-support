package kingsk.grails.lsp.services

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import kingsk.grails.lsp.GrailsService
import kingsk.grails.lsp.model.enums.ErrorSource
import kingsk.grails.lsp.model.enums.ErrorSeverity
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.providers.document.BaseProvider
import kingsk.grails.lsp.utils.diagnostics.DiagnosticUtils
import org.codehaus.groovy.control.ErrorCollector
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient

import java.util.concurrent.CompletableFuture

import kingsk.grails.lsp.GrailsService

/** Provides diagnostics for workspace and documents using the Grails compiler. */
@Slf4j
@CompileStatic
class GrailsDiagnosticService {
    private Map<String, Set<Diagnostic>> currentDiagnostics = [:]
    private final GrailsService service

    GrailsDiagnosticService(GrailsService service) {
        this.service = service
    }

    Map<String, Set<Diagnostic>> getCurrentDiagnostics() {
        return Collections.unmodifiableMap(new HashMap<String, Set<Diagnostic>>(currentDiagnostics))
    }

    protected LanguageClient getClient() { return service.client }

    /**
     * Provides incremental workspace diagnostics for the entire workspace.
     * @param identifier The additional identifier provided during registration.
     * @param previousResultIds The currently known diagnostic reports with their previous result ids.
     * @return A WorkspaceDiagnosticReport containing updated or unchanged diagnostic reports.
     */
    CompletableFuture<WorkspaceDiagnosticReport> provideWorkspaceDiagnostics(String identifier, List<PreviousResultId> previousResultIds) {
        if (!service.workspaceManager.getDefaultProject()?.compiler.errorCollectorOrNull) {
            return CompletableFuture.completedFuture(new WorkspaceDiagnosticReport([]))
        }

        log.debug("[DIAGNOSTICS] Generating workspace diagnostics for $identifier")
        Map<String, Set<Diagnostic>> newDiagnostics = extractDiagnostics(service.workspaceManager.getDefaultProject()?.compiler.errorCollectorOrNull)

        if (newDiagnostics.isEmpty()) {
            service.errorService.handleError("No diagnostics found for $identifier", null, ErrorSource.LANGUAGE_SERVER, ErrorSeverity.INFO)
            clearAllDiagnostics()
            return CompletableFuture.completedFuture(new WorkspaceDiagnosticReport([]))
        }

        List<WorkspaceDocumentDiagnosticReport> reports = newDiagnostics.collect { uri, diagnosticSet ->
            String newResultId = DiagnosticUtils.computeResultId(diagnosticSet) ?: "empty"
            String oldResultId = DiagnosticUtils.computeResultId(currentDiagnostics[uri]) ?: "empty"

            if (!newResultId || diagnosticSet?.empty) {
                currentDiagnostics.remove(uri)
                return null
            }

            if (newResultId == oldResultId) {
                // Diagnostics haven't changed from previous report
                return new WorkspaceDocumentDiagnosticReport(
                    new WorkspaceUnchangedDocumentDiagnosticReport(newResultId, uri, 1)
                )
            }

            log.debug "[DIAGNOSTICS] Diagnostics created for $uri"
            currentDiagnostics[uri] = diagnosticSet
            def fullReport = new WorkspaceFullDocumentDiagnosticReport(diagnosticSet.toList(), uri, 1)
            fullReport.resultId = newResultId
            new WorkspaceDocumentDiagnosticReport(fullReport)
        }.findAll()

        log.debug "[DIAGNOSTICS] Workspace diagnostics completed with ${reports.size()} reports"
        CompletableFuture.completedFuture(new WorkspaceDiagnosticReport(items: reports))
    }

    /**
     * Provides file-level diagnostics for the given text document.
     * @param textDocument The text document.
     * @param identifier The additional identifier provided during registration.
     * @param previousResultIds The result id of a previous response if provided.
     * @return A DocumentDiagnosticReport containing updated or unchanged diagnostic reports.
     */
    CompletableFuture<DocumentDiagnosticReport> provideDocumentDiagnostics(TextDocumentIdentifier textDocument, String identifier, String previousResultId) {
        String uri = TextFile.normalizePath(textDocument.uri)
        def projectContext = service.workspaceManager.getProjectForUri(uri)
        ErrorCollector errorCollector = projectContext?.compiler?.errorCollectorOrNull
        if (errorCollector == null) {
            return CompletableFuture.completedFuture(new DocumentDiagnosticReport(
                new RelatedFullDocumentDiagnosticReport(resultId: "empty", items: [])
            ))
        }

        log.debug "[DIAGNOSTICS] Running diagnostics for document: $uri"

        // Extract fresh diagnostics
        log.debug("[DIAGNOSTICS] Generating diagnostics for $uri")
        Set<Diagnostic> newDiagnostics = extractDiagnostics(errorCollector).getOrDefault(uri, [] as Set)
        String newResultId = DiagnosticUtils.computeResultId(newDiagnostics) ?: "empty"
        String oldResultId = DiagnosticUtils.computeResultId(currentDiagnostics[uri] ?: [] as Set) ?: "empty"

        if (!newResultId || newDiagnostics?.empty) {
            currentDiagnostics.remove(uri)
            def emptyReport = new RelatedFullDocumentDiagnosticReport(resultId: "empty", items: [])
            return CompletableFuture.completedFuture(new DocumentDiagnosticReport(emptyReport))
        }

        // Check if diagnostics have changed
        if (newResultId == oldResultId) {
            def unchanged = new RelatedUnchangedDocumentDiagnosticReport(resultId: newResultId)
            return CompletableFuture.completedFuture(new DocumentDiagnosticReport(unchanged))
        }

        // Update diagnostics
        currentDiagnostics[uri] = newDiagnostics
        log.debug "[DIAGNOSTICS] Diagnostics updated for $uri"
        def fullReport = new RelatedFullDocumentDiagnosticReport(resultId: newResultId, items: newDiagnostics.toList())
        CompletableFuture.completedFuture(new DocumentDiagnosticReport(fullReport))
    }

    /**
     * Publishes diagnostics for a specific file immediately
     * Publishes diagnostics for a specific file immediately, rejecting stale reports
     * if the document has advanced to a newer version or open generation.
     *
     * @param uri The URI of the file to publish diagnostics for
     * @param expectedVersion Optional version expected for this diagnostic run
     * @param expectedGeneration Optional open generation expected for this diagnostic run
     */
    void publishDiagnosticsForFile(String uri, Integer expectedVersion = null, Long expectedGeneration = null) {
        if (!client) {
            log.debug "[DIAGNOSTICS] No client connected, skipping diagnostic publishing for ${uri}"
            return
        }

        TextFile live = service.fileTracker.getTextFile(uri)
        if (expectedGeneration != null && live != null && !live.closed && live.openGeneration != expectedGeneration) {
            log.debug "[DIAGNOSTICS] Skipping stale diagnostic report for {} (expected gen {} != live {})",
                uri, expectedGeneration, live.openGeneration
            return
        }
        if (expectedVersion != null && live != null && !live.closed && live.version != expectedVersion) {
            log.debug "[DIAGNOSTICS] Skipping stale diagnostic report for {} (expected ver {} != live {})",
                uri, expectedVersion, live.version
            return
        }

        try {
            def diagnosticReport = provideDocumentDiagnostics(
                new TextDocumentIdentifier(uri), null, null
            ).get()

            TextFile liveAfter = service.fileTracker.getTextFile(uri)
            if (expectedGeneration != null && liveAfter != null && !liveAfter.closed && liveAfter.openGeneration != expectedGeneration) {
                log.debug "[DIAGNOSTICS] Discarding stale diagnostics for {} after calculation (gen changed)", uri
                return
            }
            if (expectedVersion != null && liveAfter != null && !liveAfter.closed && liveAfter.version != expectedVersion) {
                log.debug "[DIAGNOSTICS] Discarding stale diagnostics for {} after calculation (version changed)", uri
                return
            }

            List<Diagnostic> items = diagnosticReport?.getLeft()?.items ?: []
            PublishDiagnosticsParams params = new PublishDiagnosticsParams(uri, items)
            if (expectedVersion != null) {
                params.setVersion(expectedVersion)
            }
            client.publishDiagnostics(params)
            if (items.isEmpty()) {
                log.debug "[DIAGNOSTICS] Cleared diagnostics for {} (v={})", uri, expectedVersion
            } else {
                log.debug "[DIAGNOSTICS] Published {} diagnostics for {} (v={})", items.size(), uri, expectedVersion
            }
        } catch (Exception e) {
            log.warn "[DIAGNOSTICS] Failed to publish diagnostics for ${uri}: ${e.message}"
        }
    }

    void publishWorkspaceDiagnostics(String identifier = "grails-workspace-diagnostics") {
        if (!client) {
            log.debug "[DIAGNOSTICS] No client connected, skipping workspace diagnostic publishing"
            return
        }

        try {
            WorkspaceDiagnosticReport report = provideWorkspaceDiagnostics(identifier, null).get()

            report.items.each { documentReport ->
                if (documentReport instanceof WorkspaceFullDocumentDiagnosticReport) {
                    WorkspaceFullDocumentDiagnosticReport fullReport = (WorkspaceFullDocumentDiagnosticReport) documentReport
                    String uri = fullReport.getUri()
                    List<Diagnostic> diagnostics = fullReport.getItems()
                    client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics))
                    log.debug "[DIAGNOSTICS] Published workspace diagnostics for ${uri}: ${diagnostics.size()} items"
                }
            }
        } catch (Exception e) {
            log.warn "[DIAGNOSTICS] Failed to publish workspace diagnostics: ${e.message}"
        }
    }


    /**
     * Clears diagnostics for a specific file
     * @param uri The URI of the file to clear diagnostics for
     */
    void clearDiagnosticsForFile(String uri) {
        currentDiagnostics.remove(uri)
        if (client) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, []))
            log.debug "[DIAGNOSTICS] Cleared diagnostics for ${uri}"
        }
    }

    /**
     * Records diagnostics into the internal map for a specific file URI.
     */
    void recordDiagnostics(String uri, Collection<Diagnostic> diagnostics) {
        if (!uri) return
        if (diagnostics == null || diagnostics.isEmpty()) {
            currentDiagnostics.remove(uri)
        } else {
            currentDiagnostics.put(uri, new HashSet<Diagnostic>(diagnostics))
        }
    }

    /**
     * Publishes a list of diagnostics for a file.
     */
    void publishDiagnostics(String uri, List<Diagnostic> diagnostics) {
        if (uri) {
            recordDiagnostics(uri, diagnostics)
        }
        if (client) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics))
        }
    }

    /**
     * Clears all diagnostics cache
     */
    void clearAllDiagnostics() {
        log.debug "[DIAGNOSTICS] Clearing all diagnostics"
        currentDiagnostics.clear()
    }

    /** Extract diagnostics from compiler's errorCollector and maps them by URI */
    static Map<String, Set<Diagnostic>> extractDiagnostics(ErrorCollector errorCollector) {
        // Process errors
        Map<String, Set<Diagnostic>> diagnostics = [:]
        errorCollector.errors?.each { error ->
            Diagnostic diagnostic = DiagnosticUtils.errorToDiagnostic(error)
            if (!diagnostic) return
            diagnostics.computeIfAbsent(diagnostic.source, { new HashSet<Diagnostic>() }) << diagnostic
        }

        // Process warnings
        errorCollector.warnings?.each { warning ->
            Diagnostic diagnostic = DiagnosticUtils.warningToDiagnostic(warning)
            if (!diagnostic) return
            diagnostics.computeIfAbsent(diagnostic.source, { new HashSet<Diagnostic>() }) << diagnostic
        }

        log.debug("[DIAGNOSTICS] Extracted diagnostics across ${diagnostics.size()} files")
        diagnostics
    }
}
