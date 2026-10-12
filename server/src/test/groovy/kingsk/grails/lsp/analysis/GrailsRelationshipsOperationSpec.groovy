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
import kingsk.grails.lsp.core.analysis.GrailsRelationshipItem
import kingsk.grails.lsp.core.analysis.GrailsRelationshipsRequest
import kingsk.grails.lsp.core.analysis.GrailsRelationshipsResponse
import kingsk.grails.lsp.core.analysis.RelationshipEndpoint
import kingsk.grails.lsp.core.analysis.RelationshipType
import kingsk.grails.lsp.index.IndexManager
import kingsk.grails.lsp.index.IndexSnapshot
import kingsk.grails.lsp.model.dto.GradleModel
import kingsk.grails.lsp.model.dto.GrailsProject
import kingsk.grails.lsp.model.state.VersionedSnapshot
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.services.WorkspaceManager
import spock.lang.Subject

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Acceptance test specification for R4-02:
 * Model evidenced Grails relationships.
 *
 * Requirements:
 * R4-02/1: Queries connect routes/actions, injection, domain associations, tags,
 *          views/templates, and configuration using proven rules/declarations.
 * R4-02/2: Every relationship has scoped identities, origin/derivation, and input revisions;
 *          dynamic unknowns remain visible.
 * R4-02/3: Source/dependency deletion removes edges. Shared compact facts serve both UI
 *          and tools with bounded traversal; no AST-retaining parallel graph (INV-DISC-001/003, INV-OWN-004/005).
 */
class GrailsRelationshipsOperationSpec extends BaseLspSpec {

    @Subject
    AnalysisOperationService analysisService
    WorkspaceManager workspaceManager

    File projectDir
    String projectUri
    ProjectContextImpl projectCtx

    String authorUri
    String bookUri
    String controllerUri
    String viewUri

    def setup() {
        setupProject()
        workspaceManager = grailsService.workspaceManager
        analysisService = grailsService.analysisOperationService

        projectDir = new File(System.getProperty("user.dir"), "build/test_relationships_proj")
        projectDir.mkdirs()
        projectUri = TextFile.normalizePath(projectDir.toURI().toString())

        GrailsProject proj = new GrailsProject(
            name: "RelationshipTestApp",
            rootDirectory: projectDir,
            isGrailsProject: true,
            grailsVersion: "7.0.0-RC1",
            groovyVersion: "4.0.23",
            targetCompatibility: "17"
        )
        workspaceManager.addProject(proj)
        projectCtx = workspaceManager.getProjectForUri(projectUri)

        authorUri = TextFile.normalizePath(new File(projectDir, "grails-app/domain/Author.groovy").toURI().toString())
        bookUri = TextFile.normalizePath(new File(projectDir, "grails-app/domain/Book.groovy").toURI().toString())
        controllerUri = TextFile.normalizePath(new File(projectDir, "grails-app/controllers/BookController.groovy").toURI().toString())
        viewUri = TextFile.normalizePath(new File(projectDir, "grails-app/views/book/index.gsp").toURI().toString())
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
            new GradleModel(name: "RelationshipTestApp", isGrailsProject: true, grailsVersion: "7.0.0-RC1", groovyVersion: "4.0.23"),
            DetachedASTAccessor.INSTANCE,
            [:],
            System.currentTimeMillis()
        )
        projectCtx.snapshotManager.commit(snap, true)
    }

    def "R4-02/1: Queries connect domain associations, routes, injection, views, and configuration"() {
        given: "A project with evidenced relationships across multiple categories"
        GrailsRelationshipItem relDomain = new GrailsRelationshipItem(
            "rel:Author:hasMany:books",
            RelationshipType.DOMAIN_ASSOCIATION.value,
            new RelationshipEndpoint("Author", "Domain", authorUri, null, "Author", "books"),
            new RelationshipEndpoint("Book", "Domain", bookUri, null, "Book", null),
            AnalysisOriginKind.CAPABILITY_ADAPTER.value,
            AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
            null,
            [associationType: "hasMany"]
        )

        GrailsRelationshipItem relInject = new GrailsRelationshipItem(
            "rel:BookController:inject:bookService",
            RelationshipType.INJECTION.value,
            new RelationshipEndpoint("BookController", "Controller", controllerUri, null, "BookController", "bookService"),
            new RelationshipEndpoint("BookService", "Service", null, null, "BookService", null),
            AnalysisOriginKind.CAPABILITY_ADAPTER.value,
            AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
            null,
            [serviceBean: "bookService"]
        )

        GrailsRelationshipItem relRoute = new GrailsRelationshipItem(
            "rel:BookController#index:route:/book/index",
            RelationshipType.ROUTE_TO_ACTION.value,
            new RelationshipEndpoint("/book/index", "Route", controllerUri, null, null, null),
            new RelationshipEndpoint("BookController#index", "Action", controllerUri, null, "BookController", "index"),
            AnalysisOriginKind.CAPABILITY_ADAPTER.value,
            AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
            null,
            [pattern: "/book/index"]
        )

        GrailsRelationshipItem relView = new GrailsRelationshipItem(
            "rel:BookController#index:conventionView:index",
            RelationshipType.RENDERS_VIEW.value,
            new RelationshipEndpoint("BookController#index", "Action", controllerUri, null, "BookController", "index"),
            new RelationshipEndpoint("index.gsp", "View", viewUri, null, null, "index"),
            AnalysisOriginKind.CAPABILITY_ADAPTER.value,
            AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
            null,
            [viewPath: "views/book/index.gsp"]
        )

        seedRelationships([relDomain, relInject, relRoute, relView])

        when: "Querying all relationships for the project"
        GrailsRelationshipsRequest req = new GrailsRelationshipsRequest(projectUri: projectUri)
        GrailsRelationshipsResponse resp = analysisService.executeRelationships(req)

        then: "All relationship categories are returned with valid envelope"
        resp.schemaVersion == 1
        resp.projectUri == projectUri
        resp.freshness == AnalysisFreshness.CURRENT.value
        resp.completeness == AnalysisCompleteness.COMPLETE.value
        resp.relationships.size() == 4
        resp.relationships.any { it.type == RelationshipType.DOMAIN_ASSOCIATION.value }
        resp.relationships.any { it.type == RelationshipType.INJECTION.value }
        resp.relationships.any { it.type == RelationshipType.ROUTE_TO_ACTION.value }
        resp.relationships.any { it.type == RelationshipType.RENDERS_VIEW.value }

        when: "Filtering by type DOMAIN_ASSOCIATION"
        GrailsRelationshipsRequest filterReq = new GrailsRelationshipsRequest(
            projectUri: projectUri,
            types: [RelationshipType.DOMAIN_ASSOCIATION.value]
        )
        GrailsRelationshipsResponse filterResp = analysisService.executeRelationships(filterReq)

        then: "Only domain associations are returned"
        filterResp.relationships.size() == 1
        filterResp.relationships[0].id == "rel:Author:hasMany:books"
    }

    def "R4-02/2: Relationships have scoped identities, origin/derivation, input revisions, and explicit dynamic unknowns"() {
        given: "A relationship item with an unresolved dynamic route"
        GrailsRelationshipItem dynRoute = new GrailsRelationshipItem(
            'rel:urlmapping:wildcard:/$controller/$action?/$id?()',
            RelationshipType.ROUTE_TO_ACTION.value,
            new RelationshipEndpoint("/\$controller/\$action?/\$id?()", "Route", controllerUri, null, null, null),
            new RelationshipEndpoint("DynamicAction", "Unknown", null, null, null, null),
            AnalysisOriginKind.DECLARATION.value,
            AnalysisResolutionKind.UNRESOLVED.value,
            null,
            [dynamicRoute: true, unknownReason: "Wildcard route matches controllers and actions dynamically at runtime"]
        )
        seedRelationships([dynRoute])

        when: "Querying relationships"
        GrailsRelationshipsRequest req = new GrailsRelationshipsRequest(projectUri: projectUri)
        GrailsRelationshipsResponse resp = analysisService.executeRelationships(req)

        then: "The relationship preserves scoped identities and analysisRevision"
        resp.analysisRevision.startsWith("rev:1:dep:")
        resp.relationships.size() == 1

        GrailsRelationshipItem item = resp.relationships[0]
        item.source.name == "/\$controller/\$action?/\$id?()"
        item.source.kind == "Route"
        item.target.name == "DynamicAction"
        item.target.kind == "Unknown"
        item.resolution == "unresolved"
        item.origin == "DECLARATION"
        item.details.dynamicRoute == true
    }

    def "R4-02/3: Bounded traversal terminates on cyclic relationships and respects pagination limits"() {
        given: "A cyclic graph: Author -> hasMany -> Book, Book -> belongsTo -> Author"
        GrailsRelationshipItem authorToBook = new GrailsRelationshipItem(
            "rel:Author->Book",
            RelationshipType.DOMAIN_ASSOCIATION.value,
            new RelationshipEndpoint("Author", "Domain", authorUri, null, "Author", "books"),
            new RelationshipEndpoint("Book", "Domain", bookUri, null, "Book", null),
            AnalysisOriginKind.CAPABILITY_ADAPTER.value,
            AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
            null,
            [associationType: "hasMany"]
        )

        GrailsRelationshipItem bookToAuthor = new GrailsRelationshipItem(
            "rel:Book->Author",
            RelationshipType.DOMAIN_ASSOCIATION.value,
            new RelationshipEndpoint("Book", "Domain", bookUri, null, "Book", "author"),
            new RelationshipEndpoint("Author", "Domain", authorUri, null, "Author", null),
            AnalysisOriginKind.CAPABILITY_ADAPTER.value,
            AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
            null,
            [associationType: "belongsTo"]
        )

        seedRelationships([authorToBook, bookToAuthor])

        when: "Traversing with depth 3 starting from Author (cycle test)"
        GrailsRelationshipsRequest req = new GrailsRelationshipsRequest(
            projectUri: projectUri,
            sourceUri: authorUri,
            depth: 3,
            direction: "both"
        )
        GrailsRelationshipsResponse resp = analysisService.executeRelationships(req)

        then: "Traversal terminates safely with exactly 2 unique edges visited"
        resp.relationships.size() == 2
        resp.completeness == AnalysisCompleteness.COMPLETE.value

        when: "Requesting pagination with limit = 1"
        GrailsRelationshipsRequest pagedReq = new GrailsRelationshipsRequest(
            projectUri: projectUri,
            limit: 1
        )
        GrailsRelationshipsResponse pagedResp = analysisService.executeRelationships(pagedReq)

        then: "Result is PARTIAL with a valid nextCursor"
        pagedResp.relationships.size() == 1
        pagedResp.completeness == AnalysisCompleteness.PARTIAL.value
        pagedResp.nextCursor != null
        pagedResp.totalEstimated == 2

        when: "Resuming with nextCursor"
        GrailsRelationshipsRequest resumeReq = new GrailsRelationshipsRequest(
            projectUri: projectUri,
            cursor: pagedResp.nextCursor
        )
        GrailsRelationshipsResponse resumeResp = analysisService.executeRelationships(resumeReq)

        then: "The second item is returned"
        resumeResp.relationships.size() == 1
        resumeResp.relationships[0].id != pagedResp.relationships[0].id
    }

    def "R4-02/3: File eviction cleanly removes both outbound and inbound relationship edges"() {
        given: "Relationships between Author, Book, and Controller"
        GrailsRelationshipItem rel1 = new GrailsRelationshipItem(
            "rel:Author->Book",
            RelationshipType.DOMAIN_ASSOCIATION.value,
            new RelationshipEndpoint("Author", "Domain", authorUri, null, "Author", "books"),
            new RelationshipEndpoint("Book", "Domain", bookUri, null, "Book", null),
            AnalysisOriginKind.CAPABILITY_ADAPTER.value,
            AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
            null,
            [:]
        )

        GrailsRelationshipItem rel2 = new GrailsRelationshipItem(
            "rel:BookController->Book",
            RelationshipType.INJECTION.value,
            new RelationshipEndpoint("BookController", "Controller", controllerUri, null, "BookController", null),
            new RelationshipEndpoint("Book", "Domain", bookUri, null, "Book", null),
            AnalysisOriginKind.CAPABILITY_ADAPTER.value,
            AnalysisResolutionKind.FRAMEWORK_DERIVED.value,
            null,
            [:]
        )

        seedRelationships([rel1, rel2])

        when: "Querying before eviction"
        GrailsRelationshipsRequest beforeReq = new GrailsRelationshipsRequest(projectUri: projectUri)
        GrailsRelationshipsResponse beforeResp = analysisService.executeRelationships(beforeReq)

        then: "Both edges exist"
        beforeResp.relationships.size() == 2

        when: "Book.groovy is deleted and evicted from the index"
        IndexManager idxMgr = new IndexManager(projectCtx.projectIndex, projectCtx.methodScopeCache, projectCtx.groovydocCache)
        idxMgr.evictFile(bookUri)

        // Snapshot update after eviction
        VersionedSnapshot snap = new VersionedSnapshot(
            2L,
            projectCtx.projectIndex.snapshot,
            new GradleModel(name: "RelationshipTestApp", isGrailsProject: true, grailsVersion: "7.0.0-RC1", groovyVersion: "4.0.23"),
            DetachedASTAccessor.INSTANCE,
            [:],
            System.currentTimeMillis()
        )
        projectCtx.snapshotManager.commit(snap, true)

        GrailsRelationshipsRequest afterReq = new GrailsRelationshipsRequest(projectUri: projectUri)
        GrailsRelationshipsResponse afterResp = analysisService.executeRelationships(afterReq)

        then: "All edges touching Book.groovy (both inbound and outbound) are cleanly removed"
        afterResp.relationships.isEmpty()
    }
}
