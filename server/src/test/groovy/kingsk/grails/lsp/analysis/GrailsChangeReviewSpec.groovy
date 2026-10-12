package kingsk.grails.lsp.analysis

import kingsk.grails.lsp.GrailsService
import kingsk.grails.lsp.context.ProjectContextImpl
import kingsk.grails.lsp.core.analysis.*
import kingsk.grails.lsp.core.check.CheckExecutionResponse
import kingsk.grails.lsp.core.check.CheckStatus
import kingsk.grails.lsp.core.check.CheckSummary
import kingsk.grails.lsp.core.refactor.RenameEdit
import kingsk.grails.lsp.model.state.SnapshotManager
import kingsk.grails.lsp.model.state.VersionedSnapshot
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.services.FileContentTracker
import kingsk.grails.lsp.services.GrailsWorkspaceService
import kingsk.grails.lsp.services.WorkspaceManager
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import spock.lang.Specification

import java.util.concurrent.CompletableFuture

class GrailsChangeReviewSpec extends Specification {

    GrailsService grailsService
    WorkspaceManager workspaceManager
    ProjectContextImpl projectContext
    SnapshotManager snapshotManager
    VersionedSnapshot snapshot
    FileContentTracker fileTracker
    AnalysisOperationService analysisService
    GrailsWorkspaceService workspaceService

    String projectUri = "file:///workspace/demo-app"

    def setup() {
        grailsService = Mock(GrailsService)
        workspaceManager = Mock(WorkspaceManager)
        projectContext = Mock(ProjectContextImpl)
        snapshotManager = Mock(SnapshotManager)
        snapshot = new VersionedSnapshot(42L, null, null, null, [:], 0L)
        fileTracker = Mock(FileContentTracker)

        grailsService.workspaceManager >> workspaceManager
        grailsService.fileTracker >> fileTracker

        snapshotManager.active >> snapshot
        projectContext.snapshotManager >> snapshotManager
        projectContext.lastInvalidationRevision >> 3L
        projectContext.isDependencyDirty() >> false
        projectContext.isGradleSyncStale() >> false

        workspaceManager.getProject(projectUri) >> projectContext
        workspaceManager.getProjectForUri(projectUri) >> projectContext

        analysisService = new AnalysisOperationService(workspaceManager, grailsService)
        grailsService.analysisOperationService >> analysisService

        workspaceService = new GrailsWorkspaceService(grailsService)
    }

    def "R5-04/1: Unified change review connects edits, impact, checks, and assumptions under single identity"() {
        given:
        String fileUri = "file:///workspace/demo-app/grails-app/controllers/BookController.groovy"
        TextFile textFile = TextFile.create(fileUri, "class BookController {}")
        textFile.version = 1
        fileTracker.getTextFile(fileUri) >> textFile

        RenameEdit edit = new RenameEdit(
            fileUri,
            1,
            new Range(new Position(0, 6), new Position(0, 20)),
            "VolumeController",
            "BookController definition rename"
        )

        ChangeImpactResponse impact = new ChangeImpactResponse(
            projectUri: projectUri,
            analysisRevision: "rev:42:dep:3",
            freshness: "current",
            completeness: "complete",
            changedUris: [fileUri],
            impactedItems: [
                new ChangeImpactItem(fileUri, "BookController", "controller", "Direct edit target", "high")
            ],
            dynamicUnknowns: [],
            limitations: []
        )

        CheckExecutionResponse checks = new CheckExecutionResponse(
            projectUri: projectUri,
            checkType: "test",
            status: CheckStatus.SUCCESS.value,
            summary: new CheckSummary(
                testsTotal: 12,
                testsPassed: 12,
                testsFailed: 0,
                testsSkipped: 0,
                compilationSuccess: true,
                errorCount: 0,
                warningCount: 0
            ),
            limitations: []
        )

        ChangeReviewRequest req = new ChangeReviewRequest(
            projectUri: projectUri,
            changeId: "change-101",
            edits: [edit],
            impact: impact,
            checks: checks,
            assumptions: ["Standard Grails controller conventions observed"]
        )

        when: "Executing unified change review"
        ChangeReviewResponse resp = analysisService.executeChangeReview(req)

        then: "Change review connects all facets under changeId and analysisRevision"
        resp != null
        resp.changeId == "change-101"
        resp.projectUri == projectUri
        resp.analysisRevision == "rev:42:dep:3"
        resp.freshness == "current"
        resp.status == ChangeReviewStatus.READY
        resp.safetyVerdict == ChangeSafetyVerdict.SAFE_TO_APPLY
        resp.editsSummary.totalFiles == 1
        resp.editsSummary.totalEdits == 1
        resp.editsSummary.files == [fileUri]
        resp.impactSummary.impactedCount == 1
        resp.impactSummary.dynamicUnknownsCount == 0
        resp.checksSummary.checkStatus == "success"
        resp.checksSummary.testsPassed == 12
        resp.uncheckedAssumptions.contains("Standard Grails controller conventions observed")
        resp.limitations.any { it.contains("Generated prose alone does not constitute approval") }
    }

    def "R5-04/2: Buffer version advance triggers STALE_BUFFER and STALE_REVIEW"() {
        given: "An edit predicated on document version 1, but buffer has advanced to version 2"
        String fileUri = "file:///workspace/demo-app/grails-app/controllers/AuthorController.groovy"
        TextFile advancedBuffer = TextFile.create(fileUri, "class AuthorController { def index() {} }")
        advancedBuffer.version = 2
        fileTracker.getTextFile(fileUri) >> advancedBuffer

        RenameEdit staleEdit = new RenameEdit(
            fileUri,
            1, // Expects version 1
            new Range(new Position(0, 6), new Position(0, 22)),
            "WriterController",
            "AuthorController rename"
        )

        ChangeReviewRequest req = new ChangeReviewRequest(
            projectUri: projectUri,
            changeId: "change-stale-buf",
            edits: [staleEdit]
        )

        when: "Reviewing change with stale buffer"
        ChangeReviewResponse resp = analysisService.executeChangeReview(req)

        then: "Status is STALE_BUFFER and verdict is STALE_REVIEW with recovery guidance"
        resp != null
        resp.status == ChangeReviewStatus.STALE_BUFFER
        resp.safetyVerdict == ChangeSafetyVerdict.STALE_REVIEW
        resp.freshness == "invalidated"
        resp.recoveryActions.any { it.contains("buffer version has advanced from v1 to v2") }
    }

    def "R5-04/3: Failed check triggers CHECKS_FAILED and UNSAFE_TO_APPLY"() {
        given: "A change where scoped tests or compilation failed"
        String fileUri = "file:///workspace/demo-app/grails-app/services/PaymentService.groovy"
        TextFile textFile = TextFile.create(fileUri, "class PaymentService {}")
        textFile.version = 1
        fileTracker.getTextFile(fileUri) >> textFile

        RenameEdit edit = new RenameEdit(
            fileUri,
            1,
            new Range(new Position(0, 6), new Position(0, 20)),
            "BillingService",
            "PaymentService rename"
        )

        CheckExecutionResponse failedChecks = new CheckExecutionResponse(
            projectUri: projectUri,
            checkType: "test",
            status: CheckStatus.FAILURE.value,
            summary: new CheckSummary(
                testsTotal: 8,
                testsPassed: 6,
                testsFailed: 2,
                testsSkipped: 0,
                compilationSuccess: false,
                errorCount: 2,
                warningCount: 0
            ),
            limitations: []
        )

        ChangeReviewRequest req = new ChangeReviewRequest(
            projectUri: projectUri,
            changeId: "change-failed-checks",
            edits: [edit],
            checks: failedChecks
        )

        when: "Reviewing change with failing checks"
        ChangeReviewResponse resp = analysisService.executeChangeReview(req)

        then: "Status is CHECKS_FAILED and verdict is UNSAFE_TO_APPLY"
        resp != null
        resp.status == ChangeReviewStatus.CHECKS_FAILED
        resp.safetyVerdict == ChangeSafetyVerdict.UNSAFE_TO_APPLY
        resp.recoveryActions.any { it.contains("Resolve 2 check/compilation error(s)") }
    }

    def "R5-04/4: Dynamic unknowns in impact downgrade verdict to REQUIRES_ATTENTION"() {
        given: "A change with dynamic reflection or GSP references"
        String fileUri = "file:///workspace/demo-app/grails-app/domain/Book.groovy"
        TextFile textFile = TextFile.create(fileUri, "class Book {}")
        textFile.version = 1
        fileTracker.getTextFile(fileUri) >> textFile

        RenameEdit edit = new RenameEdit(
            fileUri,
            1,
            new Range(new Position(0, 6), new Position(0, 10)),
            "Volume",
            "Book domain rename"
        )

        ChangeImpactResponse impactWithUnknowns = new ChangeImpactResponse(
            projectUri: projectUri,
            analysisRevision: "rev:42:dep:3",
            freshness: "current",
            completeness: "partial",
            changedUris: [fileUri],
            impactedItems: [],
            dynamicUnknowns: [
                new DynamicUnknownItem("file:///views/book/show.gsp", 'Dynamic taglib reference ${book.title}', "high", "Verify GSP manually")
            ],
            limitations: []
        )

        CheckExecutionResponse cleanChecks = new CheckExecutionResponse(
            projectUri: projectUri,
            checkType: "compile",
            status: CheckStatus.SUCCESS.value,
            summary: new CheckSummary(
                compilationSuccess: true,
                errorCount: 0,
                warningCount: 0
            )
        )

        ChangeReviewRequest req = new ChangeReviewRequest(
            projectUri: projectUri,
            changeId: "change-dynamic",
            edits: [edit],
            impact: impactWithUnknowns,
            checks: cleanChecks
        )

        when: "Reviewing change with dynamic unknowns"
        ChangeReviewResponse resp = analysisService.executeChangeReview(req)

        then: "Verdict requires attention due to unprovable dynamic constructs"
        resp != null
        resp.status == ChangeReviewStatus.READY
        resp.safetyVerdict == ChangeSafetyVerdict.REQUIRES_ATTENTION
        resp.uncheckedAssumptions.any { it.contains("Dynamic references (1 item(s)) cannot be statically proven") }
    }

    def "R5-04/5: Workspace service routes grails.analysis.changeReview command successfully"() {
        given: "LSP executeCommand invocation with arguments"
        String fileUri = "file:///workspace/demo-app/grails-app/controllers/TagController.groovy"
        TextFile textFile = TextFile.create(fileUri, "class TagController {}")
        textFile.version = 1
        fileTracker.getTextFile(fileUri) >> textFile

        Map argMap = [
            projectUri: projectUri,
            changeId: "change-cmd-test",
            edits: [
                [
                    uri: fileUri,
                    documentVersion: 1,
                    range: [start: [line: 0, character: 6], end: [line: 0, character: 19]],
                    newText: "LabelController",
                    evidence: "TagController declaration"
                ]
            ],
            assumptions: ["Command routing verified"]
        ]

        ExecuteCommandParams params = new ExecuteCommandParams("grails.analysis.changeReview", [argMap])

        when: "LSP workspace service executes command"
        CompletableFuture<Object> future = workspaceService.executeCommand(params)
        Object result = future.get()

        then: "Command completes with ChangeReviewResponse"
        result instanceof ChangeReviewResponse
        ChangeReviewResponse resp = (ChangeReviewResponse) result
        resp.changeId == "change-cmd-test"
        resp.status == ChangeReviewStatus.READY
        resp.safetyVerdict == ChangeSafetyVerdict.REQUIRES_ATTENTION // Because no checks were run
        resp.uncheckedAssumptions.contains("No scoped checks executed for this change.")
    }
}
