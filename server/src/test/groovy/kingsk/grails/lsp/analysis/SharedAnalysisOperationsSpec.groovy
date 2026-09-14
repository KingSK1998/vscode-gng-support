package kingsk.grails.lsp.analysis

import kingsk.grails.lsp.test.BaseLspSpec
import kingsk.grails.lsp.context.DetachedASTAccessor
import kingsk.grails.lsp.context.ProjectContextImpl
import kingsk.grails.lsp.core.analysis.AnalysisCompleteness
import kingsk.grails.lsp.core.analysis.AnalysisErrorCode
import kingsk.grails.lsp.core.analysis.AnalysisFreshness
import kingsk.grails.lsp.core.analysis.AnalysisOperationException
import kingsk.grails.lsp.core.analysis.AnalysisOperationService
import kingsk.grails.lsp.core.analysis.AnalysisOriginKind
import kingsk.grails.lsp.core.analysis.AnalysisResolutionKind
import kingsk.grails.lsp.core.analysis.CapabilityDescriptor
import kingsk.grails.lsp.core.analysis.ProjectCapabilitiesRequest
import kingsk.grails.lsp.core.analysis.ProjectCapabilitiesResponse
import kingsk.grails.lsp.core.analysis.ProjectDiagnosticsRequest
import kingsk.grails.lsp.core.analysis.ProjectDiagnosticsResponse
import kingsk.grails.lsp.core.analysis.SymbolExplainRequest
import kingsk.grails.lsp.core.analysis.SymbolExplainResponse
import kingsk.grails.lsp.core.analysis.SymbolSearchRequest
import kingsk.grails.lsp.core.analysis.SymbolSearchResponse
import kingsk.grails.lsp.index.IndexSnapshot
import kingsk.grails.lsp.index.SymbolInfo
import kingsk.grails.lsp.model.dto.GradleModel
import kingsk.grails.lsp.model.dto.GrailsProject
import kingsk.grails.lsp.model.enums.GrailsArtifactType
import kingsk.grails.lsp.model.state.VersionedSnapshot
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.services.WorkspaceManager
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentItem
import spock.lang.Subject

import java.io.File
import java.util.function.BooleanSupplier

/**
 * Acceptance test specification for R3-01:
 * Define shared typed analysis operations.
 *
 * Requirements:
 * R3-01/1: Define schemas/internal operations for project.capabilities, symbols.search,
 *          symbols.explain, and project.diagnostics with scope, revisions, per-item provenance,
 *          and current/stale/partial/unavailable states.
 * R3-01/2: Validate inputs, errors, cancellation, count/byte caps, and revision-bound cursors;
 *          distinguish unavailable from a successful empty result.
 * R3-01/3: Tests read the existing analysis state through these operations, including unsaved
 *          inputs and cross-project revision vectors where applicable; no second agent index (INV-TOOL-001).
 */
class SharedAnalysisOperationsSpec extends BaseLspSpec {

    @Subject
    AnalysisOperationService analysisService
    WorkspaceManager workspaceManager

    File projectDir
    String projectUri
    ProjectContextImpl projectCtx

    def setup() {
        setupProject()
        workspaceManager = grailsService.workspaceManager
        analysisService = grailsService.analysisOperationService

        projectDir = new File(System.getProperty("user.dir"), "build/test_shared_analysis_proj")
        projectDir.mkdirs()
        projectUri = projectDir.toURI().toString()

        GrailsProject proj = new GrailsProject(
            name: "TestGrailsApp",
            rootDirectory: projectDir,
            isGrailsProject: true,
            grailsVersion: "7.0.0-RC1",
            groovyVersion: "4.0.23",
            targetCompatibility: "17"
        )
        workspaceManager.addProject(proj)
        projectCtx = workspaceManager.getProjectForUri(projectUri)
    }

    def cleanup() {
        if (projectDir?.exists()) {
            projectDir.deleteDir()
        }
    }

    def "R3-01/1: project.capabilities returns schema-compliant capabilities, scopes, revisions, and freshness states"() {
        given: "A registered project with initial versioned snapshot"
        VersionedSnapshot snap = new VersionedSnapshot(
            1L,
            IndexSnapshot.EMPTY,
            new GradleModel(name: "TestGrailsApp", isGrailsProject: true, grailsVersion: "7.0.0-RC1", groovyVersion: "4.0.23"),
            DetachedASTAccessor.INSTANCE,
            [:],
            System.currentTimeMillis()
        )
        projectCtx.snapshotManager.commit(snap, true)

        when: "Requesting project capabilities"
        ProjectCapabilitiesResponse resp = analysisService.executeProjectCapabilities(
            new ProjectCapabilitiesRequest(projectUri: projectUri)
        )

        then: "Envelope contains valid project metadata, revision, and tested compatibility"
        resp.projectUri == projectUri
        resp.projectName == "TestGrailsApp"
        resp.projectType == "grails"
        resp.analysisRevision == "rev:1:dep:0"
        resp.grailsVersion == "7.0.0-RC1"
        resp.groovyVersion == "4.0.23"
        resp.javaVersion == "17"
        resp.compatibilityLevel == "TESTED"
        resp.freshness == AnalysisFreshness.CURRENT.value
        resp.completeness == AnalysisCompleteness.COMPLETE.value

        and: "All admitted capability adapters are reported with architectural descriptors"
        resp.capabilities.size() == 4
        List<String> capNames = resp.capabilities.collect { it.name }
        capNames.containsAll(["GORM", "GrailsInjection", "TagLib", "GrailsConfig"])
        resp.capabilities.every { CapabilityDescriptor cap ->
            cap.name && cap.detectionEvidence && cap.applicability && cap.derivation
        }

        when: "Project dependencies are marked dirty or Gradle sync is stale"
        projectCtx.markDependencyDirty("test-dependency-change", 1L)

        and: "Re-requesting project capabilities"
        ProjectCapabilitiesResponse staleResp = analysisService.executeProjectCapabilities(
            new ProjectCapabilitiesRequest(projectUri: projectUri)
        )

        then: "Freshness updates to stale and reasons explain the state"
        staleResp.freshness == AnalysisFreshness.STALE.value
        staleResp.reasons.any { it.contains("dirty") || it.contains("pending") }
    }

    def "R3-01/1: symbols.search and symbols.explain return schema-compliant symbol items with per-item provenance"() {
        given: "A snapshot containing domain and controller symbols"
        Range dummyRange = new Range(new Position(10, 0), new Position(20, 0))
        Range selRange = new Range(new Position(10, 6), new Position(10, 10))
        String fileUri = projectUri + "/grails-app/domain/Book.groovy"

        SymbolInfo bookSym = new SymbolInfo(
            "org.example.Book",
            "org.example",
            "Book",
            SymbolKind.Class,
            dummyRange,
            selRange,
            "class Book",
            "void",
            GrailsArtifactType.DOMAIN,
            0,
            fileUri,
            null
        )
        SymbolInfo controllerSym = new SymbolInfo(
            "org.example.BookController",
            "org.example",
            "BookController",
            SymbolKind.Class,
            dummyRange,
            selRange,
            "class BookController",
            "void",
            GrailsArtifactType.CONTROLLER,
            0,
            projectUri + "/grails-app/controllers/BookController.groovy",
            null
        )

        Map<String, SymbolInfo> byDesc = [
            (bookSym.descriptor()): bookSym,
            (controllerSym.descriptor()): controllerSym
        ]
        Map<String, List<SymbolInfo>> byUri = [
            (fileUri): [bookSym],
            (controllerSym.fileUri()): [controllerSym]
        ]
        IndexSnapshot idx = new IndexSnapshot(byDesc, byUri, [:])

        VersionedSnapshot snap = new VersionedSnapshot(
            2L,
            idx,
            new GradleModel(name: "TestGrailsApp", isGrailsProject: true, grailsVersion: "7.0.0-RC1", groovyVersion: "4.0.23"),
            DetachedASTAccessor.INSTANCE,
            [:],
            System.currentTimeMillis()
        )
        projectCtx.snapshotManager.commit(snap, true)

        when: "Searching for symbols matching 'Book'"
        SymbolSearchResponse searchResp = analysisService.executeSymbolSearch(
            new SymbolSearchRequest(projectUri: projectUri, query: "Book")
        )

        then: "Search response matches schema and returns indexed items with provenance"
        searchResp.projectUri == projectUri
        searchResp.analysisRevision == "rev:2:dep:0"
        searchResp.freshness == AnalysisFreshness.CURRENT.value
        searchResp.completeness == AnalysisCompleteness.COMPLETE.value
        searchResp.totalCount == 2
        searchResp.items.size() == 2

        searchResp.items.any {
            it.name == "Book" &&
            it.kind == "Class" &&
            it.uri == fileUri &&
            it.originKind == AnalysisOriginKind.INDEX.value &&
            it.resolutionKind == AnalysisResolutionKind.DECLARATION_BACKED.value &&
            it.evidence != null
        }
        searchResp.items.any {
            it.name == "BookController" &&
            it.kind == "Class" &&
            it.originKind == AnalysisOriginKind.INDEX.value
        }

        when: "Explaining symbol 'Book' by documentUri and line/character position"
        SymbolExplainResponse explainResp = analysisService.executeSymbolExplain(
            new SymbolExplainRequest(
                projectUri: projectUri,
                documentUri: fileUri,
                line: 10,
                character: 7
            )
        )

        then: "Explain response resolves symbol definition and provides provenance evidence"
        explainResp.symbol != null
        explainResp.symbol.name == "Book"
        explainResp.symbol.containerDescriptor == "org.example"
        explainResp.analysisRevision == "rev:2:dep:0"
        explainResp.freshness == AnalysisFreshness.CURRENT.value
        explainResp.completeness == AnalysisCompleteness.COMPLETE.value
        explainResp.symbol.evidence != null
        explainResp.symbol.evidence.sourceUri == fileUri
    }

    def "R3-01/1: project.diagnostics returns schema-compliant diagnostic items with scope and revisions"() {
        given: "Recorded diagnostics in the project"
        String docUri = projectUri + "/grails-app/controllers/BookController.groovy"
        Diagnostic diag = new Diagnostic(
            new Range(new Position(15, 4), new Position(15, 12)),
            "Undefined variable: unknownVar",
            DiagnosticSeverity.Error,
            "grails-compiler"
        )
        diag.setCode("GROOVY-001")
        grailsService.diagnostics.recordDiagnostics(docUri, [diag])

        VersionedSnapshot snap = new VersionedSnapshot(
            3L,
            IndexSnapshot.EMPTY,
            new GradleModel(name: "TestGrailsApp", isGrailsProject: true, grailsVersion: "7.0.0-RC1", groovyVersion: "4.0.23"),
            DetachedASTAccessor.INSTANCE,
            [:],
            System.currentTimeMillis()
        )
        projectCtx.snapshotManager.commit(snap, true)

        when: "Querying project diagnostics"
        ProjectDiagnosticsResponse diagResp = analysisService.executeProjectDiagnostics(
            new ProjectDiagnosticsRequest(projectUri: projectUri)
        )

        then: "Diagnostics response matches schema and reports items with correct provenance"
        diagResp.projectUri == projectUri
        diagResp.analysisRevision == "rev:3:dep:0"
        diagResp.freshness == AnalysisFreshness.CURRENT.value
        diagResp.completeness == AnalysisCompleteness.COMPLETE.value
        diagResp.diagnostics.size() == 1
        diagResp.totalCount == 1

        def item = diagResp.diagnostics[0]
        item.uri == docUri
        item.message == "Undefined variable: unknownVar"
        item.severity == "Error"
        item.source == "grails-compiler"
        item.code == "GROOVY-001"
        item.range.start.line == 15
        item.range.start.character == 4
    }

    def "R3-01/2: Input validation rejects invalid requests with INVALID_INPUT error code"() {
        when: "Executing symbol search with negative limit"
        analysisService.executeSymbolSearch(new SymbolSearchRequest(projectUri: projectUri, query: "Book", limit: -1))

        then: "AnalysisOperationException is thrown with INVALID_INPUT"
        AnalysisOperationException ex1 = thrown()
        ex1.errorCode == AnalysisErrorCode.INVALID_INPUT

        when: "Executing symbol explain with empty documentUri and symbolName"
        analysisService.executeSymbolExplain(new SymbolExplainRequest(projectUri: projectUri, documentUri: ""))

        then: "AnalysisOperationException is thrown with INVALID_INPUT"
        AnalysisOperationException ex2 = thrown()
        ex2.errorCode == AnalysisErrorCode.INVALID_INPUT

        when: "Executing diagnostics with invalid severity"
        analysisService.executeProjectDiagnostics(new ProjectDiagnosticsRequest(projectUri: projectUri, severity: "invalidSeverity"))

        then: "AnalysisOperationException is thrown with INVALID_INPUT"
        AnalysisOperationException ex3 = thrown()
        ex3.errorCode == AnalysisErrorCode.INVALID_INPUT
    }

    def "R3-01/2: Distinguish UNAVAILABLE project from successful empty search result"() {
        given: "A valid project with empty index"
        VersionedSnapshot snap = new VersionedSnapshot(
            1L,
            IndexSnapshot.EMPTY,
            new GradleModel(name: "TestGrailsApp", isGrailsProject: true, grailsVersion: "7.0.0-RC1", groovyVersion: "4.0.23"),
            DetachedASTAccessor.INSTANCE,
            [:],
            System.currentTimeMillis()
        )
        projectCtx.snapshotManager.commit(snap, true)

        when: "Searching an unknown / unregistered project URI"
        analysisService.executeSymbolSearch(new SymbolSearchRequest(projectUri: "file:///does/not/exist/proj", query: "Anything"))

        then: "Throws UNAVAILABLE error code"
        AnalysisOperationException ex = thrown()
        ex.errorCode == AnalysisErrorCode.UNAVAILABLE

        when: "Searching a known project for a non-existent symbol"
        SymbolSearchResponse resp = analysisService.executeSymbolSearch(
            new SymbolSearchRequest(projectUri: projectUri, query: "DefinitelyNonExistentSymbol_XYZ_12345")
        )

        then: "Does NOT throw UNAVAILABLE; returns successful empty result with COMPLETE completeness"
        resp.items.isEmpty()
        resp.totalCount == 0
        resp.completeness == AnalysisCompleteness.COMPLETE.value
        !resp.hasMore
    }

    def "R3-01/2: Cancellation aborts operations with CANCELLED error code"() {
        given: "A cancellation check supplier that returns true"
        BooleanSupplier cancelled = { true }

        when: "Executing capabilities with cancellation check"
        analysisService.executeProjectCapabilities(new ProjectCapabilitiesRequest(projectUri: projectUri), cancelled)

        then: "AnalysisOperationException is thrown with CANCELLED"
        AnalysisOperationException ex1 = thrown()
        ex1.errorCode == AnalysisErrorCode.CANCELLED

        when: "Executing search with cancellation check"
        analysisService.executeSymbolSearch(new SymbolSearchRequest(projectUri: projectUri, query: "Book"), cancelled)

        then: "AnalysisOperationException is thrown with CANCELLED"
        AnalysisOperationException ex2 = thrown()
        ex2.errorCode == AnalysisErrorCode.CANCELLED
    }

    def "R3-01/2: Count caps, byte budget caps, and cursor pagination"() {
        given: "A snapshot containing 5 symbols"
        Range r = new Range(new Position(0, 0), new Position(0, 5))
        Map<String, SymbolInfo> byDesc = [:]
        for (int i = 1; i <= 5; i++) {
            String name = "Item${i}".toString()
            SymbolInfo s = new SymbolInfo(name, "pkg", name, SymbolKind.Class, r, r, "", "void", GrailsArtifactType.UNKNOWN, 0, projectUri + "/Item${i}.groovy", null)
            byDesc.put(name, s)
        }
        IndexSnapshot idx = new IndexSnapshot(byDesc, [:], [:])
        VersionedSnapshot snap = new VersionedSnapshot(
            10L,
            idx,
            new GradleModel(name: "TestGrailsApp", isGrailsProject: true, grailsVersion: "7.0.0-RC1", groovyVersion: "4.0.23"),
            DetachedASTAccessor.INSTANCE,
            [:],
            System.currentTimeMillis()
        )
        projectCtx.snapshotManager.commit(snap, true)

        when: "Searching with limit cap = 2"
        SymbolSearchResponse page1 = analysisService.executeSymbolSearch(
            new SymbolSearchRequest(projectUri: projectUri, query: "Item", limit: 2)
        )

        then: "Page 1 contains exactly 2 items, hasMore is true, and nextCursor is returned"
        page1.items.size() == 2
        page1.hasMore
        page1.nextCursor != null
        page1.completeness == AnalysisCompleteness.PARTIAL.value

        when: "Fetching Page 2 with the nextCursor from Page 1"
        SymbolSearchResponse page2 = analysisService.executeSymbolSearch(
            new SymbolSearchRequest(projectUri: projectUri, query: "Item", limit: 2, cursor: page1.nextCursor)
        )

        then: "Page 2 contains the next 2 items with distinct names"
        page2.items.size() == 2
        page2.hasMore
        page2.nextCursor != null
        page2.items[0].name != page1.items[0].name
        page2.items[0].name != page1.items[1].name

        when: "A new snapshot version is committed to the project (revision advance)"
        VersionedSnapshot snapAdv = new VersionedSnapshot(
            11L,
            idx,
            new GradleModel(name: "TestGrailsApp", isGrailsProject: true, grailsVersion: "7.0.0-RC1", groovyVersion: "4.0.23"),
            DetachedASTAccessor.INSTANCE,
            [:],
            System.currentTimeMillis()
        )
        projectCtx.snapshotManager.commit(snapAdv, true)

        and: "Requesting next page with the old cursor from version 10"
        analysisService.executeSymbolSearch(
            new SymbolSearchRequest(projectUri: projectUri, query: "Item", cursor: page1.nextCursor)
        )

        then: "Throws CURSOR_EXPIRED due to revision advance"
        AnalysisOperationException expiredEx = thrown()
        expiredEx.errorCode == AnalysisErrorCode.CURSOR_EXPIRED

        when: "Searching with tight byte budget"
        SymbolSearchResponse byteCapped = analysisService.executeSymbolSearch(
            new SymbolSearchRequest(projectUri: projectUri, query: "Item", maxPayloadBytes: 1024)
        )

        then: "Response respects byte cap and marks result as partial"
        byteCapped.completeness == AnalysisCompleteness.PARTIAL.value
        byteCapped.hasMore
        byteCapped.nextCursor != null
    }

    def "R3-01/3: Reads existing analysis state directly without second agent index (INV-TOOL-001)"() {
        given: "Unsaved buffer in FileContentTracker (never written to disk)"
        String unsavedUri = projectUri + "/grails-app/domain/UnsavedSpecialEntity.groovy"
        String unsavedCode = """
package org.example

class UnsavedSpecialEntity {
    String title
}
"""
        grailsService.fileTracker.didOpenFile(
            new DidOpenTextDocumentParams(new TextDocumentItem(unsavedUri, "groovy", 1, unsavedCode)),
            false
        )

        when: "Querying symbols through analysis operation"
        SymbolSearchResponse resp = analysisService.executeSymbolSearch(
            new SymbolSearchRequest(projectUri: projectUri, query: "UnsavedSpecialEntity")
        )

        then: "Symbol is discovered directly from memory buffer overlay without disk persistence or secondary agent index"
        resp.items.any {
            it.name == "UnsavedSpecialEntity.groovy" &&
            TextFile.normalizePath(it.uri) == TextFile.normalizePath(unsavedUri) &&
            it.evidence?.rule == "unsaved-buffer"
        }
    }

    def "R3-01/3: Cross-project revision vectors maintained independently"() {
        given: "A second project registered in workspace manager"
        File projectDir2 = new File(System.getProperty("user.dir"), "build/test_shared_proj2")
        projectDir2.mkdirs()
        String uri2 = projectDir2.toURI().toString()

        GrailsProject proj2 = new GrailsProject(
            name: "ProjectTwo",
            rootDirectory: projectDir2,
            isGrailsProject: true,
            grailsVersion: "7.0.0-RC1",
            groovyVersion: "4.0.23",
            targetCompatibility: "17"
        )
        workspaceManager.addProject(proj2)
        ProjectContextImpl ctx2 = workspaceManager.getProjectForUri(uri2)

        and: "Different snapshot revisions committed to project 1 and project 2"
        projectCtx.snapshotManager.commit(
            new VersionedSnapshot(1L, IndexSnapshot.EMPTY, null, DetachedASTAccessor.INSTANCE, [:], 0),
            true
        )
        ctx2.snapshotManager.commit(
            new VersionedSnapshot(7L, IndexSnapshot.EMPTY, null, DetachedASTAccessor.INSTANCE, [:], 0),
            true
        )

        when: "Querying capabilities for both projects"
        ProjectCapabilitiesResponse resp1 = analysisService.executeProjectCapabilities(
            new ProjectCapabilitiesRequest(projectUri: projectUri)
        )
        ProjectCapabilitiesResponse resp2 = analysisService.executeProjectCapabilities(
            new ProjectCapabilitiesRequest(projectUri: uri2)
        )

        then: "Both projects maintain isolated, independent analysis revisions"
        resp1.analysisRevision == "rev:1:dep:0"
        resp2.analysisRevision == "rev:7:dep:0"
        resp1.projectName == "TestGrailsApp"
        resp2.projectName == "ProjectTwo"

        cleanup:
        if (projectDir2?.exists()) {
            projectDir2.deleteDir()
        }
    }
}
