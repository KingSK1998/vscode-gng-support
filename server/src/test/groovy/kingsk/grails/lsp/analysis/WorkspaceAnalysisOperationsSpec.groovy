package kingsk.grails.lsp.analysis

import com.google.gson.JsonObject
import kingsk.grails.lsp.test.BaseLspSpec
import kingsk.grails.lsp.context.DetachedASTAccessor
import kingsk.grails.lsp.context.ProjectContextImpl
import kingsk.grails.lsp.core.analysis.AnalysisErrorCode
import kingsk.grails.lsp.core.analysis.AnalysisErrorResponse
import kingsk.grails.lsp.core.analysis.ProjectCapabilitiesResponse
import kingsk.grails.lsp.core.analysis.ProjectDiagnosticsResponse
import kingsk.grails.lsp.core.analysis.SymbolExplainResponse
import kingsk.grails.lsp.core.analysis.SymbolSearchResponse
import kingsk.grails.lsp.index.IndexSnapshot
import kingsk.grails.lsp.index.SymbolInfo
import kingsk.grails.lsp.model.dto.GradleModel
import kingsk.grails.lsp.model.dto.GrailsProject
import kingsk.grails.lsp.model.enums.GrailsArtifactType
import kingsk.grails.lsp.model.state.VersionedSnapshot
import kingsk.grails.lsp.services.GrailsWorkspaceService
import kingsk.grails.lsp.services.WorkspaceManager
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import spock.lang.Subject

class WorkspaceAnalysisOperationsSpec extends BaseLspSpec {

    @Subject
    GrailsWorkspaceService workspaceService
    WorkspaceManager workspaceManager

    File projectDir
    String projectUri
    ProjectContextImpl projectCtx

    def setup() {
        setupProject()
        workspaceService = grailsService.workspace as GrailsWorkspaceService
        workspaceManager = grailsService.workspaceManager

        projectDir = new File(System.getProperty("user.dir"), "build/test_workspace_analysis_proj")
        projectDir.mkdirs()
        projectUri = projectDir.toURI().toString()

        GrailsProject proj = new GrailsProject(
            name: "WorkspaceAnalysisApp",
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

    def "R3-02/1: workspace executeCommand handles grails.analysis.projectCapabilities with Map or JsonObject"() {
        given: "A committed snapshot in the project"
        VersionedSnapshot snap = new VersionedSnapshot(
            1L,
            IndexSnapshot.EMPTY,
            new GradleModel(name: "WorkspaceAnalysisApp", isGrailsProject: true, grailsVersion: "7.0.0-RC1", groovyVersion: "4.0.23"),
            DetachedASTAccessor.INSTANCE,
            [:],
            System.currentTimeMillis()
        )
        projectCtx.snapshotManager.commit(snap, true)

        when: "Executing with Map argument"
        ExecuteCommandParams paramsMap = new ExecuteCommandParams(
            "grails.analysis.projectCapabilities",
            [[projectUri: projectUri]]
        )
        Object res1 = workspaceService.executeCommand(paramsMap).get()

        and: "Executing with JsonObject argument"
        JsonObject json = new JsonObject()
        json.addProperty("projectUri", projectUri)
        ExecuteCommandParams paramsJson = new ExecuteCommandParams(
            "grails.analysis.projectCapabilities",
            [json]
        )
        Object res2 = workspaceService.executeCommand(paramsJson).get()

        then: "Both invocations return typed ProjectCapabilitiesResponse"
        res1 instanceof ProjectCapabilitiesResponse
        (res1 as ProjectCapabilitiesResponse).projectName == "WorkspaceAnalysisApp"
        (res1 as ProjectCapabilitiesResponse).grailsVersion == "7.0.0-RC1"
        (res1 as ProjectCapabilitiesResponse).capabilities.any { it.name == "GORM" }

        res2 instanceof ProjectCapabilitiesResponse
        (res2 as ProjectCapabilitiesResponse).projectName == "WorkspaceAnalysisApp"
    }

    def "R3-02/1: workspace executeCommand handles grails.analysis.symbolSearch and symbolExplain"() {
        given: "A snapshot containing indexed symbols"
        Range dummyRange = new Range(new Position(10, 0), new Position(20, 0))
        Range selRange = new Range(new Position(10, 6), new Position(10, 20))
        String docUri = projectUri + "/grails-app/controllers/TestController.groovy"
        SymbolInfo sym = new SymbolInfo(
            "org.example.TestController",
            "org.example",
            "TestController",
            SymbolKind.Class,
            dummyRange,
            selRange,
            "class TestController",
            "void",
            GrailsArtifactType.CONTROLLER,
            0,
            docUri,
            null
        )
        Map<String, SymbolInfo> byDesc = [(sym.descriptor()): sym]
        Map<String, List<SymbolInfo>> byUri = [(docUri): [sym]]
        IndexSnapshot idx = new IndexSnapshot(byDesc, byUri, [:])
        VersionedSnapshot snap = new VersionedSnapshot(
            1L,
            idx,
            new GradleModel(name: "WorkspaceAnalysisApp", isGrailsProject: true),
            DetachedASTAccessor.INSTANCE,
            [:],
            System.currentTimeMillis()
        )
        projectCtx.snapshotManager.commit(snap, true)

        when: "Searching symbols via workspace executeCommand"
        ExecuteCommandParams searchParams = new ExecuteCommandParams(
            "grails.analysis.symbolSearch",
            [[projectUri: projectUri, query: "TestController"]]
        )
        Object searchRes = workspaceService.executeCommand(searchParams).get()

        and: "Explaining symbol via workspace executeCommand"
        ExecuteCommandParams explainParams = new ExecuteCommandParams(
            "grails.analysis.symbolExplain",
            [[projectUri: projectUri, documentUri: docUri, symbolName: "TestController"]]
        )
        Object explainRes = workspaceService.executeCommand(explainParams).get()

        then: "Symbol search returns matching symbol item"
        searchRes instanceof SymbolSearchResponse
        (searchRes as SymbolSearchResponse).items.size() == 1
        (searchRes as SymbolSearchResponse).items[0].name == "TestController"

        and: "Symbol explain returns matched declaration symbol"
        explainRes instanceof SymbolExplainResponse
        (explainRes as SymbolExplainResponse).symbol != null
        (explainRes as SymbolExplainResponse).symbol.name == "TestController"
    }

    def "R3-02/1: workspace executeCommand handles grails.analysis.projectDiagnostics"() {
        given: "Recorded diagnostics on the server"
        String docUri = new File(projectDir, "grails-app/services/TestService.groovy").toURI().toString()
        org.eclipse.lsp4j.Diagnostic diag = new org.eclipse.lsp4j.Diagnostic(
            new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(5, 2), new org.eclipse.lsp4j.Position(5, 12)),
            "Undefined property",
            org.eclipse.lsp4j.DiagnosticSeverity.Error,
            "GrailsCompiler"
        )
        grailsService.diagnostics.recordDiagnostics(docUri, [diag])

        when: "Querying project diagnostics via workspace executeCommand"
        ExecuteCommandParams diagParams = new ExecuteCommandParams(
            "grails.analysis.projectDiagnostics",
            [[projectUri: projectUri]]
        )
        Object diagRes = workspaceService.executeCommand(diagParams).get()

        then: "Diagnostics are returned in envelope"
        diagRes instanceof ProjectDiagnosticsResponse
        (diagRes as ProjectDiagnosticsResponse).diagnostics.size() == 1
        (diagRes as ProjectDiagnosticsResponse).diagnostics[0].message == "Undefined property"
        (diagRes as ProjectDiagnosticsResponse).diagnostics[0].severity == "Error"
    }

    def "R3-02/2: workspace executeCommand catches AnalysisOperationException and returns typed error response"() {
        when: "Querying capabilities for a non-existent project"
        ExecuteCommandParams missingProjParams = new ExecuteCommandParams(
            "grails.analysis.projectCapabilities",
            [[projectUri: "file:///non/existent/path"]]
        )
        Object missingRes = workspaceService.executeCommand(missingProjParams).get()

        and: "Passing invalid input (missing required query)"
        ExecuteCommandParams invalidParams = new ExecuteCommandParams(
            "grails.analysis.symbolSearch",
            [[projectUri: projectUri]]
        )
        Object invalidRes = workspaceService.executeCommand(invalidParams).get()

        then: "Missing project returns AnalysisErrorResponse with UNAVAILABLE code"
        missingRes instanceof AnalysisErrorResponse
        (missingRes as AnalysisErrorResponse).code == AnalysisErrorCode.UNAVAILABLE
        (missingRes as AnalysisErrorResponse).schemaVersion == 1

        and: "Invalid input returns AnalysisErrorResponse with INVALID_INPUT code"
        invalidRes instanceof AnalysisErrorResponse
        (invalidRes as AnalysisErrorResponse).code == AnalysisErrorCode.INVALID_INPUT
        (invalidRes as AnalysisErrorResponse).schemaVersion == 1

        when: "Executing with empty arguments list"
        ExecuteCommandParams emptyArgParams = new ExecuteCommandParams(
            "grails.analysis.projectCapabilities",
            []
        )
        Object emptyArgRes = workspaceService.executeCommand(emptyArgParams).get()

        then: "Empty arguments return typed AnalysisErrorResponse with INVALID_INPUT code"
        emptyArgRes instanceof AnalysisErrorResponse
        (emptyArgRes as AnalysisErrorResponse).code == AnalysisErrorCode.INVALID_INPUT
        (emptyArgRes as AnalysisErrorResponse).error.contains("Missing arguments")
    }
}
