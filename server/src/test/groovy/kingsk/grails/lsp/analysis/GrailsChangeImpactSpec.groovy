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
import kingsk.grails.lsp.core.analysis.ChangeImpactItem
import kingsk.grails.lsp.core.analysis.ChangeImpactRequest
import kingsk.grails.lsp.core.analysis.ChangeImpactResponse
import kingsk.grails.lsp.core.analysis.DynamicUnknownItem
import kingsk.grails.lsp.core.analysis.GrailsRelationshipItem
import kingsk.grails.lsp.core.analysis.RelationshipEndpoint
import kingsk.grails.lsp.core.analysis.RelationshipType
import kingsk.grails.lsp.core.analysis.VerificationSuggestion
import kingsk.grails.lsp.index.IndexManager
import kingsk.grails.lsp.index.IndexSnapshot
import kingsk.grails.lsp.index.SymbolInfo
import kingsk.grails.lsp.model.dto.GradleModel
import kingsk.grails.lsp.model.dto.GrailsProject
import kingsk.grails.lsp.model.state.VersionedSnapshot
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.services.WorkspaceManager
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import spock.lang.Subject

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Acceptance test specification for R5-01:
 * Conservative Change Impact Analysis.
 *
 * Requirements:
 * R5-01/1: Computes impacted controllers, services, domains, taglibs, views, and configuration
 *          bindings through proven relationship edges.
 * R5-01/2: Dynamic finders, wildcard URL mappings, dynamic dispatch, or unresolved endpoints
 *          are explicitly reported as dynamic unknowns with bounded confidence and limitations.
 * R5-01/3: Cyclic references terminate gracefully; bounded traversal prevents unbounded graph expansion;
 *          cursor pagination or limit bounding applies; file deletion / unknown targets handled safely.
 */
class GrailsChangeImpactSpec extends BaseLspSpec {

    @Subject
    AnalysisOperationService analysisService
    WorkspaceManager workspaceManager

    File projectDir
    String projectUri
    ProjectContextImpl projectCtx

    String domainUri
    String serviceUri
    String controllerUri
    String viewUri
    String taglibUri
    String configUri
    String testSpecUri

    def setup() {
        setupProject()
        workspaceManager = grailsService.workspaceManager
        analysisService = grailsService.analysisOperationService

        projectDir = new File(System.getProperty("user.dir"), "build/test_impact_proj")
        projectDir.mkdirs()
        projectUri = TextFile.normalizePath(projectDir.toURI().toString())

        GrailsProject proj = new GrailsProject(
            name: "ImpactTestApp",
            rootDirectory: projectDir,
            isGrailsProject: true,
            grailsVersion: "7.0.0-RC1",
            groovyVersion: "4.0.23",
            targetCompatibility: "17"
        )
        workspaceManager.addProject(proj)
        projectCtx = workspaceManager.getProjectForUri(projectUri)

        domainUri = TextFile.normalizePath(new File(projectDir, "grails-app/domain/Book.groovy").toURI().toString())
        serviceUri = TextFile.normalizePath(new File(projectDir, "grails-app/services/BookService.groovy").toURI().toString())
        controllerUri = TextFile.normalizePath(new File(projectDir, "grails-app/controllers/BookController.groovy").toURI().toString())
        viewUri = TextFile.normalizePath(new File(projectDir, "grails-app/views/book/show.gsp").toURI().toString())
        taglibUri = TextFile.normalizePath(new File(projectDir, "grails-app/taglib/FormatTagLib.groovy").toURI().toString())
        configUri = TextFile.normalizePath(new File(projectDir, "grails-app/conf/application.yml").toURI().toString())
        testSpecUri = TextFile.normalizePath(new File(projectDir, "src/test/groovy/BookControllerSpec.groovy").toURI().toString())
    }

    def cleanup() {
        if (projectDir?.exists()) {
            projectDir.deleteDir()
        }
    }

    private void seedRelationships(List<GrailsRelationshipItem> items) {
        IndexSnapshot current = projectCtx.projectIndex.snapshot
        IndexSnapshot next = new IndexSnapshot(current.byDescriptor, current.byUri, current.referencesByName, items)
        projectCtx.projectIndex.commit(next)

        VersionedSnapshot snap = new VersionedSnapshot(
            1L,
            next,
            new GradleModel(name: "ImpactTestApp", isGrailsProject: true, grailsVersion: "7.0.0-RC1", groovyVersion: "4.0.23"),
            DetachedASTAccessor.INSTANCE,
            [:],
            System.currentTimeMillis()
        )
        projectCtx.snapshotManager.commit(snap, true)
    }

    def "R5-01/1: Computes impacted artefacts across services, controllers, views, taglibs, and configs"() {
        given: "A graph where BookService is injected by BookController, which renders show.gsp"
        RelationshipEndpoint serviceEp = new RelationshipEndpoint("BookService", "service", serviceUri, null, "BookService", null)
        RelationshipEndpoint controllerEp = new RelationshipEndpoint("BookController", "controller", controllerUri, null, "BookController", null)
        RelationshipEndpoint viewEp = new RelationshipEndpoint("show.gsp", "view", viewUri, null, null, null)
        RelationshipEndpoint taglibEp = new RelationshipEndpoint("FormatTagLib", "taglib", taglibUri, null, "FormatTagLib", null)

        GrailsRelationshipItem injectionEdge = new GrailsRelationshipItem(
            "rel:inject:1",
            RelationshipType.INJECTION.value,
            controllerEp,
            serviceEp,
            AnalysisOriginKind.DECLARATION.value,
            AnalysisResolutionKind.DECLARATION_BACKED.value,
            null,
            [:]
        )

        GrailsRelationshipItem renderEdge = new GrailsRelationshipItem(
            "rel:render:1",
            RelationshipType.RENDERS_VIEW.value,
            controllerEp,
            viewEp,
            AnalysisOriginKind.DECLARATION.value,
            AnalysisResolutionKind.DECLARATION_BACKED.value,
            null,
            [:]
        )

        GrailsRelationshipItem tagEdge = new GrailsRelationshipItem(
            "rel:tag:1",
            RelationshipType.TAG_CALL.value,
            viewEp,
            taglibEp,
            AnalysisOriginKind.DECLARATION.value,
            AnalysisResolutionKind.DECLARATION_BACKED.value,
            null,
            [:]
        )

        seedRelationships([injectionEdge, renderEdge, tagEdge])

        when: "Change impact is requested on BookService with depth 3"
        ChangeImpactRequest req = new ChangeImpactRequest(
            projectUri: projectUri,
            changedUris: [serviceUri],
            depth: 3
        )
        ChangeImpactResponse response = analysisService.executeChangeImpact(req)

        then: "BookController and show.gsp are reported as impacted"
        response != null
        response.schemaVersion == 1
        response.changedUris == [serviceUri]
        response.impactedItems.any { it.uri == controllerUri && it.name == "BookController" }
        response.impactedItems.any { it.uri == viewUri && it.name == "show.gsp" }
        response.verificationSuggestions != null
        response.limitations.size() > 0
    }

    def "R5-01/2: Dynamic finders and wildcard mappings are reported as dynamic unknowns with limitations"() {
        given: "A domain class Book and a relationship with unresolved endpoint"
        RelationshipEndpoint domainEp = new RelationshipEndpoint("Book", "domain", domainUri, null, "Book", null)
        RelationshipEndpoint unresolvedEp = new RelationshipEndpoint("dynamicFinder", "finder", null, null, null, null)

        GrailsRelationshipItem dynamicEdge = new GrailsRelationshipItem(
            "rel:dyn:1",
            RelationshipType.DOMAIN_ASSOCIATION.value,
            domainEp,
            unresolvedEp,
            AnalysisOriginKind.DECLARATION.value,
            AnalysisResolutionKind.UNRESOLVED.value,
            null,
            [:]
        )

        seedRelationships([dynamicEdge])

        when: "Impact requested for domain Book"
        ChangeImpactRequest req = new ChangeImpactRequest(
            projectUri: projectUri,
            changedUris: [domainUri],
            depth: 2
        )
        ChangeImpactResponse response = analysisService.executeChangeImpact(req)

        then: "Dynamic unknowns are explicitly surfaced with conservative confidence and limitations"
        response != null
        response.dynamicUnknowns.size() >= 1
        response.dynamicUnknowns.any { it.description.contains("Unresolved dynamic target") }
        response.limitations.any { it.contains("Complete safety is NOT guaranteed") }
    }

    def "R5-01/3: Cyclic references terminate gracefully with bounded traversal and cursor pagination"() {
        given: "A cyclic relationship between ServiceA, ServiceB, and ServiceC"
        String serviceAUri = TextFile.normalizePath(new File(projectDir, "grails-app/services/ServiceA.groovy").toURI().toString())
        String serviceBUri = TextFile.normalizePath(new File(projectDir, "grails-app/services/ServiceB.groovy").toURI().toString())
        String serviceCUri = TextFile.normalizePath(new File(projectDir, "grails-app/services/ServiceC.groovy").toURI().toString())

        RelationshipEndpoint epA = new RelationshipEndpoint("ServiceA", "service", serviceAUri, null, "ServiceA", null)
        RelationshipEndpoint epB = new RelationshipEndpoint("ServiceB", "service", serviceBUri, null, "ServiceB", null)
        RelationshipEndpoint epC = new RelationshipEndpoint("ServiceC", "service", serviceCUri, null, "ServiceC", null)

        GrailsRelationshipItem aToB = new GrailsRelationshipItem(
            "rel:cycle:1",
            RelationshipType.INJECTION.value,
            epA,
            epB,
            AnalysisOriginKind.DECLARATION.value,
            AnalysisResolutionKind.DECLARATION_BACKED.value,
            null,
            [:]
        )

        GrailsRelationshipItem bToA = new GrailsRelationshipItem(
            "rel:cycle:2",
            RelationshipType.INJECTION.value,
            epB,
            epA,
            AnalysisOriginKind.DECLARATION.value,
            AnalysisResolutionKind.DECLARATION_BACKED.value,
            null,
            [:]
        )

        GrailsRelationshipItem aToC = new GrailsRelationshipItem(
            "rel:cycle:3",
            RelationshipType.INJECTION.value,
            epA,
            epC,
            AnalysisOriginKind.DECLARATION.value,
            AnalysisResolutionKind.DECLARATION_BACKED.value,
            null,
            [:]
        )

        seedRelationships([aToB, bToA, aToC])

        when: "Change impact requested for ServiceA with depth 3 and limit 1"
        ChangeImpactRequest req = new ChangeImpactRequest(
            projectUri: projectUri,
            changedUris: [serviceAUri],
            depth: 3,
            limit: 1
        )
        ChangeImpactResponse response = analysisService.executeChangeImpact(req)

        then: "Traversal terminates without infinite loop, respects limit, and provides nextCursor"
        response != null
        response.impactedItems.size() == 1
        response.nextCursor != null
        response.totalEstimated >= 2

        when: "Fetching next page using cursor"
        ChangeImpactRequest nextReq = new ChangeImpactRequest(
            projectUri: projectUri,
            changedUris: [serviceAUri],
            depth: 3,
            cursor: response.nextCursor,
            limit: 1
        )
        ChangeImpactResponse nextResponse = analysisService.executeChangeImpact(nextReq)

        then: "Second page is returned safely"
        nextResponse != null
        nextResponse.impactedItems.size() == 1
    }

    def "R5-01/4: Unknown target or deleted file handled safely without crashing"() {
        given: "An empty relationship graph"
        seedRelationships([])

        when: "Requesting impact for non-existent target"
        String nonexistentUri = TextFile.normalizePath(new File(projectDir, "grails-app/services/NonExistent.groovy").toURI().toString())
        ChangeImpactRequest req = new ChangeImpactRequest(
            projectUri: projectUri,
            changedUris: [nonexistentUri],
            depth: 2
        )
        ChangeImpactResponse response = analysisService.executeChangeImpact(req)

        then: "Empty impact response returned without error"
        response != null
        response.impactedItems.isEmpty()
        response.dynamicUnknowns.isEmpty()
        response.limitations.size() > 0
    }

    def "R5-01/5: Input validation and cancellation check"() {
        when: "Blank projectUri provided"
        analysisService.executeChangeImpact(new ChangeImpactRequest(projectUri: "", changedUris: [domainUri]))

        then:
        AnalysisOperationException ex = thrown(AnalysisOperationException)
        ex.errorCode == AnalysisErrorCode.INVALID_INPUT

        when: "Empty changedUris provided"
        analysisService.executeChangeImpact(new ChangeImpactRequest(projectUri: projectUri, changedUris: []))

        then:
        AnalysisOperationException ex2 = thrown(AnalysisOperationException)
        ex2.errorCode == AnalysisErrorCode.INVALID_INPUT

        when: "Cancellation is signaled"
        AtomicBoolean cancelled = new AtomicBoolean(true)
        analysisService.executeChangeImpact(
            new ChangeImpactRequest(projectUri: projectUri, changedUris: [domainUri]),
            { cancelled.get() }
        )

        then:
        AnalysisOperationException ex3 = thrown(AnalysisOperationException)
        ex3.errorCode == AnalysisErrorCode.CANCELLED
    }
}
