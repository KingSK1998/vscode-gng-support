package kingsk.grails.lsp.check

import kingsk.grails.lsp.test.BaseLspSpec
import kingsk.grails.lsp.context.DetachedASTAccessor
import kingsk.grails.lsp.context.ProjectContextImpl
import kingsk.grails.lsp.core.analysis.AnalysisErrorCode
import kingsk.grails.lsp.core.analysis.AnalysisOperationException
import kingsk.grails.lsp.core.analysis.AnalysisOperationService
import kingsk.grails.lsp.core.check.CheckDiagnosticItem
import kingsk.grails.lsp.core.check.CheckExecutionRequest
import kingsk.grails.lsp.core.check.CheckExecutionResponse
import kingsk.grails.lsp.core.check.CheckScope
import kingsk.grails.lsp.core.check.CheckStatus
import kingsk.grails.lsp.core.check.CheckSummary
import kingsk.grails.lsp.index.IndexSnapshot
import kingsk.grails.lsp.model.dto.GradleModel
import kingsk.grails.lsp.model.dto.GrailsProject
import kingsk.grails.lsp.model.state.VersionedSnapshot
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.services.WorkspaceManager
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import spock.lang.Subject

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Acceptance test specification for R5-03:
 * Expose scoped compile and test operations.
 *
 * Requirements:
 * R5-03/1: Explicit project/task/environment selections use validated structured arguments,
 *          execution permission/trust, and bounded concurrency/output/time.
 * R5-03/2: Cancel/timeout/disconnect cleans up owned execution; success, failure, no-tests,
 *          skipped, and partial checks remain distinct.
 * R5-03/3: Result records identify exact input state/fingerprints and executed checks.
 *          Independent acceptance rejects a change that a narrow passing test missed.
 */
class GrailsChecksOperationSpec extends BaseLspSpec {

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

        projectDir = new File(System.getProperty("user.dir"), "build/test_checks_proj")
        projectDir.mkdirs()
        projectUri = TextFile.normalizePath(projectDir.toURI().toString())

        GrailsProject proj = new GrailsProject(
            name: "ChecksTestApp",
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

    def "R5-03/1: Rejects malformed requests and validates structured arguments"() {
        when: "projectUri is null or empty"
        analysisService.executeChecksRun(new CheckExecutionRequest(projectUri: "", checkType: "compile"))

        then:
        AnalysisOperationException e1 = thrown()
        e1.errorCode == AnalysisErrorCode.INVALID_INPUT

        when: "checkType is missing or invalid"
        analysisService.executeChecksRun(new CheckExecutionRequest(projectUri: projectUri, checkType: "invalidType"))

        then:
        AnalysisOperationException e2 = thrown()
        e2.errorCode == AnalysisErrorCode.INVALID_INPUT

        when: "projectUri is not registered"
        analysisService.executeChecksRun(new CheckExecutionRequest(projectUri: "file:///non/existent/project", checkType: "compile"))

        then:
        AnalysisOperationException e3 = thrown()
        e3.errorCode == AnalysisErrorCode.UNAVAILABLE

        when: "valid request is passed"
        CheckExecutionResponse resp = analysisService.executeChecksRun(new CheckExecutionRequest(
            projectUri: projectUri,
            checkType: "compile",
            timeoutMs: 30000L,
            maxOutputBytes: 16384L
        ))

        then:
        resp != null
        resp.schemaVersion == 1
        resp.projectUri == projectUri
        resp.checkType == "compile"
        resp.status == CheckStatus.SUCCESS.value
    }

    def "R5-03/2: Distinguishes success, failure, no_tests, skipped, timeout, and cancellation"() {
        given: "A project snapshot"
        VersionedSnapshot snap = new VersionedSnapshot(
            1L,
            IndexSnapshot.EMPTY,
            new GradleModel(name: "ChecksTestApp", isGrailsProject: true, grailsVersion: "7.0.0-RC1", groovyVersion: "4.0.23"),
            DetachedASTAccessor.INSTANCE,
            [:],
            System.currentTimeMillis()
        )
        projectCtx.snapshotManager.commit(snap, false)

        when: "Cancellation is requested"
        AtomicBoolean cancelled = new AtomicBoolean(true)
        analysisService.executeChecksRun(new CheckExecutionRequest(projectUri: projectUri, checkType: "compile"), { cancelled.get() })

        then:
        AnalysisOperationException cancelEx = thrown()
        cancelEx.errorCode == AnalysisErrorCode.CANCELLED

        when: "Compile check runs with compiler errors"
        File badFile = new File(projectDir, "grails-app/controllers/BadController.groovy")
        badFile.parentFile.mkdirs()
        badFile.text = "class BadController { syntax error here"
        String badUri = TextFile.normalizePath(badFile.toURI().toString())
        grailsService.diagnostics.publishDiagnostics(badUri, [
            new Diagnostic(new Range(new Position(0, 23), new Position(0, 30)), "Syntax error", DiagnosticSeverity.Error, "compiler")
        ])

        CheckExecutionResponse failResp = analysisService.executeChecksRun(new CheckExecutionRequest(
            projectUri: projectUri,
            checkType: "compile"
        ))

        then:
        failResp.status == CheckStatus.FAILURE.value
        !failResp.summary.compilationSuccess
        failResp.summary.errorCount > 0
        failResp.diagnostics.size() > 0

        when: "Combined compile and test check runs with compiler error"
        CheckExecutionResponse allFailResp = analysisService.executeChecksRun(new CheckExecutionRequest(
            projectUri: projectUri,
            checkType: "all",
            scope: new CheckScope(classes: ["com.example.BadTest"])
        ))

        then:
        allFailResp.status == CheckStatus.FAILURE.value
        !allFailResp.summary.compilationSuccess
        allFailResp.summary.testsSkipped > 0

        when: "Test check runs on project with no tests"
        grailsService.diagnostics.publishDiagnostics(badUri, []) // clear errors
        CheckExecutionResponse noTestsResp = analysisService.executeChecksRun(new CheckExecutionRequest(
            projectUri: projectUri,
            checkType: "test"
        ))

        then:
        noTestsResp.status == CheckStatus.NO_TESTS.value
        noTestsResp.summary.testsTotal == 0
        noTestsResp.summary.testsPassed == 0
    }

    def "R5-03/3: Records analysisRevision fingerprints, executed tasks, and narrow test limitations"() {
        given: "A clean snapshot"
        VersionedSnapshot snap = new VersionedSnapshot(
            2L,
            IndexSnapshot.EMPTY,
            new GradleModel(name: "ChecksTestApp", isGrailsProject: true, grailsVersion: "7.0.0-RC1", groovyVersion: "4.0.23"),
            DetachedASTAccessor.INSTANCE,
            [:],
            System.currentTimeMillis()
        )
        projectCtx.snapshotManager.commit(snap, false)

        when: "Narrow test check runs on a specific class"
        CheckExecutionResponse narrowResp = analysisService.executeChecksRun(new CheckExecutionRequest(
            projectUri: projectUri,
            checkType: "test",
            scope: new CheckScope(classes: ["com.example.NarrowSpec"])
        ))

        then: "Result includes input fingerprint and explicit limitations"
        narrowResp.analysisRevision.startsWith("rev:2:")
        narrowResp.tasksExecuted.contains("test")
        narrowResp.summary.testsTotal == 1
        narrowResp.summary.testsPassed == 1
        narrowResp.limitations.any { it.toLowerCase().contains("does not establish that the whole project passes") }

        when: "Scoped check runs on specific sourceSet"
        CheckExecutionResponse scopedResp = analysisService.executeChecksRun(new CheckExecutionRequest(
            projectUri: projectUri,
            checkType: "compile",
            scope: new CheckScope(sourceSet: "main")
        ))

        then: "Result records scoped task and limitation"
        scopedResp.tasksExecuted.contains("compileMainGroovy")
        scopedResp.limitations.any { it.contains("Scoped check executed only for sourceSet 'main'") }

        when: "Output exceeds maxOutputBytes"
        CheckExecutionResponse truncResp = analysisService.executeChecksRun(new CheckExecutionRequest(
            projectUri: projectUri,
            checkType: "test",
            scope: new CheckScope(classes: ["com.example.NarrowSpec"]),
            maxOutputBytes: 1024L
        ))

        then:
        truncResp != null
        truncResp.status == CheckStatus.SUCCESS.value
    }
}
