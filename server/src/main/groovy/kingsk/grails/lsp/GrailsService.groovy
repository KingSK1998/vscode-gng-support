package kingsk.grails.lsp

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import kingsk.grails.lsp.context.ProviderContext
import kingsk.grails.lsp.core.analysis.AnalysisOperationService
import kingsk.grails.lsp.core.compiler.GrailsCompiler
import kingsk.grails.lsp.core.visitor.GrailsASTVisitor
import kingsk.grails.lsp.model.dto.GrailsProject
import kingsk.grails.lsp.model.dto.DependencyNode
import kingsk.grails.lsp.model.config.GrailsLspConfig
import kingsk.grails.lsp.model.enums.ErrorSource
import kingsk.grails.lsp.model.enums.ErrorSeverity
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.protocol.GrailsLanguageClient
import kingsk.grails.lsp.protocol.dto.ProjectDTO
import kingsk.grails.lsp.protocol.mapper.ProjectMapper
import kingsk.grails.lsp.providers.ProviderRegistry
import kingsk.grails.lsp.providers.document.GrailsGormSqlProvider
import kingsk.grails.lsp.providers.workspace.GrailsDependencyProvider
import kingsk.grails.lsp.providers.workspace.GrailsTestDiscoveryProvider
import kingsk.grails.lsp.services.CancellationService
import kingsk.grails.lsp.services.DiscoveryService
import kingsk.grails.lsp.services.ErrorService
import kingsk.grails.lsp.services.FileContentTracker
import kingsk.grails.lsp.services.GradleService
import kingsk.grails.lsp.services.GrailsDiagnosticService
import kingsk.grails.lsp.services.GrailsTextDocumentService
import kingsk.grails.lsp.services.GrailsWorkspaceService
import kingsk.grails.lsp.services.ProgressService
import kingsk.grails.lsp.services.ProviderHealthService
import kingsk.grails.lsp.services.WorkspaceManager
import kingsk.grails.lsp.utils.cache.ThreadSafeLruCache
import kingsk.grails.lsp.utils.project.ProjectDiffUtil
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

@Slf4j
@CompileStatic
class GrailsService implements LanguageClientAware, ProviderContext {

    GrailsLanguageClient client

    private final Map<String, ProjectDTO> lastSent = new ConcurrentHashMap<>()

    @Override
    void connect(LanguageClient client) {
        this.client = client as GrailsLanguageClient
    }

    GrailsWorkspaceService workspace
    GrailsTextDocumentService document

    GradleService gradle
    FileContentTracker fileTracker
    GrailsDiagnosticService diagnostics
    ProgressService progressService
    ErrorService errorService
    DiscoveryService discoveryService
    GrailsDependencyProvider dependencyProvider
    GrailsGormSqlProvider gormSqlProvider
    GrailsTestDiscoveryProvider testDiscoveryProvider
    AnalysisOperationService analysisOperationService
    CancellationService cancellationService
    ProviderHealthService healthService
    ProviderRegistry providerRegistry

    GrailsLspConfig config

    final Executor backgroundExecutor = Executors.newFixedThreadPool(4)
    private final ReentrantReadWriteLock astLock = new ReentrantReadWriteLock()

    private SearchTier currentTier = SearchTier.TIER_0
    private long tier2StartTime = 0L
    private int oomCount = 0

    public <T> T withReadLock(groovy.lang.Closure<T> closure) {
        def lock = astLock.readLock()
        lock.lock()
        try {
            closure()
        } finally {
            lock.unlock()
        }
    }

    public <T> T withWriteLock(groovy.lang.Closure<T> closure) {
        def lock = astLock.writeLock()
        lock.lock()
        try {
            closure()
        } finally {
            lock.unlock()
        }
    }

    WorkspaceManager workspaceManager

    GrailsService() {
        this.errorService = new ErrorService(this)
        this.cancellationService = new CancellationService()
        this.healthService = new ProviderHealthService()
        this.discoveryService = new DiscoveryService()
        this.workspaceManager = new WorkspaceManager(this)
        this.analysisOperationService = new AnalysisOperationService(workspaceManager, this)
        this.providerRegistry = new ProviderRegistry(this, workspaceManager)
        this.gradle = new GradleService(this)
        this.fileTracker = new FileContentTracker(this)
        this.progressService = new ProgressService(this)
        this.diagnostics = new GrailsDiagnosticService(this)
        this.document = new GrailsTextDocumentService(this)
        this.workspace = new GrailsWorkspaceService(this)
        this.dependencyProvider = new GrailsDependencyProvider(this, workspaceManager)
        this.gormSqlProvider = new GrailsGormSqlProvider(this, workspaceManager)
        this.testDiscoveryProvider = new GrailsTestDiscoveryProvider(this, workspaceManager)
        this.config = new GrailsLspConfig()
    }

    /**
     * Clears all globally tracked symbol caches.
     */
    void clearCrossFileCaches() {
        log.debug("[GrailsService] Evicting cross-file symbol caches")
        providerRegistry.getProvider(kingsk.grails.lsp.providers.document.GrailsCompletionProvider).clearCaches()
        discoveryService.clearSymbolCaches()
        kingsk.grails.lsp.utils.grails.GroovyRuntimeIntegration.clearCaches()
        kingsk.grails.lsp.utils.grails.GroovyHelperIntegration.clearCaches()
        kingsk.grails.lsp.utils.grails.GrailsHelperIntegration.clearCaches()
    }

    ProjectDTO getProjectInfo(String projectDir) {
        String uri = new File(projectDir).toURI().toString()
        GrailsProject project = workspaceManager.getProjectForUri(uri)?.project

        if (project == null) {
            project = gradle.getGrailsProject(projectDir)
            if (project == null) return null
            workspaceManager.addProject(project)
        }

        return ProjectMapper.toDTO(project)
    }

    void notifyAllProjects() {
        if (client == null) return
        List<ProjectDTO> dtos = workspaceManager?.allProjects
            ?.findAll { it != null }
            ?.collect { ProjectMapper.toDTO(it) } ?: []

        client.notifyAllProjects(dtos)
    }

    void addProject(GrailsProject project) {
        if (project?.rootDirectory) {
            workspaceManager.addProject(project)
        }
    }

    void removeProject(String projectDir) {
        String uri = new File(projectDir).toURI().toString()
        workspaceManager.removeProject(uri)
    }

    void updateProject(GrailsProject project, String projectDir) {
        if (project && projectDir) {
            workspaceManager.addProject(project)
        }
    }

    GrailsProject getProject() {
        return workspaceManager.getDefaultProject()?.project
    }

    GrailsProject getProjectForUri(String uri) {
        return workspaceManager.getProjectForUri(uri)?.project
    }

    GrailsCompiler getCompiler() {
        return (GrailsCompiler) workspaceManager.getDefaultProject()?.compiler
    }

    GrailsASTVisitor getVisitor() {
        def project = workspaceManager.getDefaultProject()
        if (project == null) return null
        return (GrailsASTVisitor) (project.snapshotManager.active?.ast ?: project.visitor)
    }

    void compileAndVisitAST(TextFile textFile) {
        workspaceManager.getProjectForUri(textFile.uri)?.compileAndVisitAST(textFile)
    }

    void visitAST(TextFile textFile) {
        workspaceManager.getProjectForUri(textFile.uri)?.visitAST(textFile)
    }

    File getJavaDocJarFile(DependencyNode dependency) {
        GrailsProject currentProject = getProject()
        if (!dependency || !currentProject) return null

        DependencyNode dep = currentProject.dependencies.find { it == dependency }
        if (dep?.javadocFileClasspath) return dep.javadocFileClasspath

        File downloaded = gradle?.downloadJavaDocJarFile(currentProject.rootDirectory, dependency)
        if (downloaded) {
            dep?.javadocFileClasspath = downloaded
            return downloaded
        }

        return null
    }

    File getSourcesJarFile(DependencyNode dependency) {
        GrailsProject currentProject = getProject()
        if (!dependency || !currentProject) return null

        DependencyNode dep = currentProject.dependencies.find { it == dependency }
        if (dep?.sourceJarFileClasspath) return dep.sourceJarFileClasspath

        File download = gradle?.downloadSourcesJarFile(currentProject.rootDirectory, dependency)
        if (download) {
            dep?.sourceJarFileClasspath = download
            return download
        }

        return null
    }

    @Override
    boolean isTier2() {
        // REJECT_OPS is considered a super-tier of Tier 2 — all Tier 2 restrictions apply, plus complete operation rejection
        if (currentTier == SearchTier.REJECT_OPS) {
            return true
        }

        if (currentTier != SearchTier.TIER_2) {
            return false
        }

        long duration = System.currentTimeMillis() - tier2StartTime
        if (duration > 60000) {
            Runtime runtime = Runtime.getRuntime()
            long usedMemory = runtime.totalMemory() - runtime.freeMemory()
            double percentUsed = (double) usedMemory / runtime.maxMemory()
            if (percentUsed < 0.75) {
                currentTier = SearchTier.TIER_0
                oomCount = 0
                log.info("[GrailsService] Tier-2 Exit Criteria passed (cooldown > 60s, heap usage < 75%). Recovered to Tier 0.")
                return false
            }
        }

        return true
    }

    /**
     * Emergency Exit Protocol for OutOfMemoryError.
     * 3-tier escalation: Tier 2 -> Global Hibernation -> Reject Ops (keeps process alive for client recovery).
     */
    void handleOOM(OutOfMemoryError e) {
        log.error("[GrailsService] OOM detected! Running OOM recovery escalation path. Current tier: ${currentTier}, oomCount: ${oomCount}", e)

        // Evict all caches first
        try {
            clearCrossFileCaches()
        } catch (Exception ex) {
            log.error("[GrailsService] Failed to clear caches during OOM recovery", ex)
        }
        System.gc()

        oomCount++

        if (oomCount == 1) {
            currentTier = SearchTier.TIER_2
            tier2StartTime = System.currentTimeMillis()
            log.warn("[GrailsService] OOM Escalation #1: Entering Tier 2 (index-only/regex search). AST and compilation are disabled.")
        } else if (oomCount == 2) {
            log.warn("[GrailsService] OOM Escalation #2: Global hibernation. Hibernating all non-active project contexts.")
            try {
                workspaceManager?.getAllContexts()?.each { ctx ->
                    if (ctx != workspaceManager.getDefaultProject()) {
                        ctx.hibernate()
                    }
                }
            } catch (Exception ex) {
                log.error("[GrailsService] Failed to hibernate projects during OOM recovery", ex)
            }
            System.gc()
        } else {
            log.error("[GrailsService] OOM Escalation #3: Fatal memory limit reached. Rejecting all expensive operations. Server remains alive for client recovery.")
            currentTier = SearchTier.REJECT_OPS
            try {
                client?.telemetryEvent([type: "error", message: "Language Server hit fatal memory limit. All expensive operations rejected. Reload workspace to recover."] as Map)
            } catch (Exception ignored) {}
        }
    }

    void shutdown() {
        log.info("[GrailsService] Shutting down executors...")
        document?.shutdown()

        if (backgroundExecutor instanceof java.util.concurrent.ExecutorService) {
            ((java.util.concurrent.ExecutorService) backgroundExecutor).shutdown()
            try {
                if (!((java.util.concurrent.ExecutorService) backgroundExecutor).awaitTermination(5, TimeUnit.SECONDS)) {
                    ((java.util.concurrent.ExecutorService) backgroundExecutor).shutdownNow()
                }
            } catch (InterruptedException e) {
                ((java.util.concurrent.ExecutorService) backgroundExecutor).shutdownNow()
                Thread.currentThread().interrupt()
            }
        }

        fileTracker?.shutdown()
        workspace?.shutdown()
        workspaceManager?.shutdown()
        cancellationService?.cancelAll()
        ThreadSafeLruCache.shutdown()

        log.info("[GrailsService] Shutdown complete")
    }
}

enum SearchTier {
    TIER_0,
    TIER_1,
    TIER_2,
    REJECT_OPS
}
