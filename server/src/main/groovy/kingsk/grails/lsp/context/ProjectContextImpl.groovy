package kingsk.grails.lsp.context

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import kingsk.grails.lsp.GrailsService
import kingsk.grails.lsp.core.compiler.GrailsCompiler
import kingsk.grails.lsp.core.visitor.GrailsASTVisitor
import kingsk.grails.lsp.index.GroovydocCache
import kingsk.grails.lsp.index.IndexManager
import kingsk.grails.lsp.index.MethodScopeCache
import kingsk.grails.lsp.index.ProjectIndex
import kingsk.grails.lsp.model.dto.GrailsProject
import kingsk.grails.lsp.model.dto.GradleModel
import kingsk.grails.lsp.model.enums.ErrorSource
import kingsk.grails.lsp.model.state.ProjectState
import kingsk.grails.lsp.model.state.SnapshotManager
import kingsk.grails.lsp.model.state.VersionedSnapshot
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.protocol.dto.ProjectDTO
import kingsk.grails.lsp.protocol.mapper.ProjectMapper
import kingsk.grails.lsp.services.FileContentTracker
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassNode
import org.eclipse.lsp4j.Position

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/**
 * Implementation of ProjectContext that manages compilation generations.
 * ENSURES: Snapshot ↔ AST consistency by using Generation-Scoped Visitors.
 */
@Slf4j
@CompileStatic
class ProjectContextImpl implements ProjectContext, CompilationContext {

    private final GrailsService grailsService
    private GrailsProject project

    final ProjectIndex projectIndex
    final IndexManager indexManager
    final MethodScopeCache methodScopeCache
    final GroovydocCache groovydocCache

    // Live compilation state (Current Generation)
    private GrailsCompiler compiler
    private GrailsASTVisitor visitor

    // Lifecycle Tracking (Phase 3)
    private final AtomicReference<ProjectState> state = new AtomicReference<>(ProjectState.INITIALIZING)
    private final AtomicReference<CompletableFuture<Void>> activationFuture = new AtomicReference<>(null)
    private volatile long lastAccessedTime = System.currentTimeMillis()
    private volatile boolean dependencyDirty = false
    private final AtomicReference<String> lastInvalidationOrigin = new AtomicReference<>(null)
    private final AtomicLong lastInvalidationRevision = new AtomicLong(0L)
    private final AtomicBoolean isRecompilingAsync = new AtomicBoolean(false)

    // Gradle Sync Lifecycle & Freshness (R1-03)
    private final AtomicReference<CompletableFuture<GrailsProject>> currentGradleSyncFuture = new AtomicReference<>(null)
    private final AtomicLong gradleSyncSequence = new AtomicLong(0L)
    private final AtomicBoolean gradleSyncInProgress = new AtomicBoolean(false)
    private final AtomicBoolean gradleSyncStale = new AtomicBoolean(false)
    private final AtomicInteger gradleSyncRetryCount = new AtomicInteger(0)
    private final AtomicReference<String> lastSyncError = new AtomicReference<>(null)
    private final AtomicReference<CompletableFuture<?>> currentDiscoveryFuture = new AtomicReference<>(null)
    public static final int MAX_SYNC_RETRIES = 2

    private final ReentrantReadWriteLock astLock = new ReentrantReadWriteLock()
    private final AtomicLong versionCounter = new AtomicLong(0)
    // Accessed only under astLock. These are committed editor overlays, not project source inventory.
    private final Set<String> openDocumentUris = new HashSet<>()

    // Request Lease & Retention Tracking (R1-04)
    private final AtomicInteger activeLeases = new AtomicInteger(0)
    private final List<Runnable> drainListeners = new java.util.concurrent.CopyOnWriteArrayList<>()

    // Atomic source of truth for the entire project (Lineage & LKG)
    final SnapshotManager snapshotManager

    ProjectContextImpl(GrailsProject project, GrailsService grailsService) {
        this.@project = project
        this.grailsService = grailsService

        this.projectIndex = new ProjectIndex(project.name ?: "root")
        this.methodScopeCache = new MethodScopeCache()
        this.groovydocCache = new GroovydocCache(100)
        this.indexManager = new IndexManager(projectIndex, methodScopeCache, groovydocCache)

        // Initialize SnapshotManager with safe detached ASTAccessor
        this.snapshotManager = new SnapshotManager(
            new VersionedSnapshot(0, projectIndex.snapshot, toGradleModel(project), DetachedASTAccessor.INSTANCE, [:], System.currentTimeMillis())
        )

        // Initialize the first generation
        this.@compiler = new GrailsCompiler(grailsService, this)
        this.@visitor = new GrailsASTVisitor(grailsService)
    }

    // --- Core Atomic Compilation & Commit Protocol (GAP-10) ---

    @Override
    void compileAndVisitAST(TextFile textFile) {
        compileAndVisitAST(textFile, null)
    }

    /** A queued document revision may be superseded or closed while the compiler is busy. */
    void compileAndVisitAST(TextFile textFile, BooleanSupplier currentRevision) {
        if (!textFile) return
        if (this.@state.get() == ProjectState.DISPOSING || (currentRevision != null && !currentRevision.asBoolean)) return
        if (grailsService.isTier2()) {
            log.warn("[ProjectContext] Bypassing AST compilation in Tier 2 for: ${textFile.uri}")
            return
        }
        ensureActivated()

        boolean committed = false
        withWriteLock {
            if (this.@state.get() == ProjectState.DISPOSING || this.@compiler == null) return
            if (currentRevision != null) {
                if (!currentRevision.asBoolean) return
                TextFile live = grailsService.fileTracker.getTextFile(textFile.uri)
                if (live != null && (live.closed || (live.open && live.openGeneration != textFile.openGeneration) || live.version != textFile.version)) {
                    log.debug("[ProjectContext] Stale compilation candidate rejected for {}", textFile.uri)
                    return
                }
                this.@compiler.markDirty(textFile.uri)
            }
            if (!this.@compiler.isDirty(textFile.uri) && this.@compiler.compilationExistsFor(textFile.uri)) {
                return
            }

            this.@compiler.markDirty(textFile.uri)
            this.@compiler.compileSourceFile(textFile)

            if (this.@state.get() == ProjectState.DISPOSING || (currentRevision != null && !currentRevision.asBoolean)) return
            if (currentRevision != null) {
                TextFile liveMid = grailsService.fileTracker.getTextFile(textFile.uri)
                if (liveMid != null && (liveMid.closed || (liveMid.open && liveMid.openGeneration != textFile.openGeneration) || liveMid.version != textFile.version)) {
                    log.debug("[ProjectContext] Stale candidate rejected before visit for {}", textFile.uri)
                    return
                }
            }

            def sourceUnit = this.@compiler.getSourceUnit(textFile)
            if (sourceUnit) {
                this.@visitor.visitSourceUnit(sourceUnit)
                if (currentRevision != null && !currentRevision.asBoolean) return
                def classNodes = sourceUnit.AST?.classes
                if (classNodes != null) indexManager.rebuildFile(textFile.uri, classNodes)
            }
            if (currentRevision != null && !currentRevision.asBoolean) return
            if (currentRevision != null || grailsService.fileTracker.getOpenGeneration(textFile.uri) != null) {
                TextFile liveFinal = grailsService.fileTracker.getTextFile(textFile.uri)
                if (liveFinal != null && (liveFinal.closed || (liveFinal.openGeneration != 0L && liveFinal.openGeneration != textFile.openGeneration) || liveFinal.version != textFile.version)) {
                    log.debug("[ProjectContext] Stale candidate rejected before commit for {}", textFile.uri)
                    return
                }
                openDocumentUris.add(TextFile.normalizePath(textFile.uri))
            }
            commitSnapshotLocked()
            grailsService.clearCrossFileCaches()
            committed = true
        }
        if (!committed) return
        grailsService.workspaceManager?.propagateInvalidation(this)
        if (currentRevision != null && !currentRevision.asBoolean) return
        try {
            grailsService.diagnostics.publishDiagnosticsForFile(textFile.uri, textFile.version, textFile.openGeneration)
        } catch (Exception e) {
            log.error("[ProjectContext] Failed to publish diagnostics for {}: {}", textFile.uri, e.message)
        }
    }

    @Override
    void visitAST(TextFile textFile) {
        if (this.@state.get() == ProjectState.DISPOSING) return
        ensureActivated()
        withWriteLock {
            if (this.@state.get() == ProjectState.DISPOSING || this.@compiler == null) return
            def sourceUnit = this.@compiler.getSourceUnit(textFile)
            if (sourceUnit) {
                this.@visitor.visitSourceUnit(sourceUnit)
                def classNodes = sourceUnit.AST?.classes
                if (classNodes != null) indexManager.rebuildFile(textFile.uri, classNodes)
            }
            commitSnapshotLocked()
        }
        grailsService.workspaceManager?.propagateInvalidation(this)
    }

    /**
     * Commits the current generation as an immutable VersionedSnapshot.
     * SWAPS: The visitor instance to ensure the committed one is never cleared.
     */
    void commitSnapshot() {
        withWriteLock { commitSnapshotLocked() }
        grailsService.workspaceManager?.propagateInvalidation(this)
    }

    private void commitSnapshotLocked() {
        if (this.@state.get() == ProjectState.DISPOSING) return
        long ver = versionCounter.incrementAndGet()

        // CAPTURE: The current visitor as a stable ASTAccessor for this snapshot
        ASTAccessor snapshotAccessor = (ASTAccessor) this.@visitor

        def newSnapshot = new VersionedSnapshot(
            ver,
            projectIndex.snapshot,
            toGradleModel(this.@project),
            snapshotAccessor,
            [:],
            System.currentTimeMillis()
        )

        // ATOMIC SWAP: Delegate to SnapshotManager to track lineage and LKG state
        boolean isSuccess = this.@compiler != null && !this.@compiler.errorCollectorOrNull?.hasErrors()
        snapshotManager.commit(newSnapshot, isSuccess)

        // GENERATION SWAP: Create a fresh visitor for the next compilation cycle.
        // We copy the visitor maps so that files not compiled in the current cycle
        // are retained, while ensuring the committed snapshot's visitor maps remain frozen/untouched.
        GrailsASTVisitor nextVisitor = new GrailsASTVisitor(grailsService)
        if (snapshotAccessor instanceof GrailsASTVisitor) {
            nextVisitor.copyFrom((GrailsASTVisitor) snapshotAccessor)
        }
        this.@visitor = nextVisitor

        log.debug("[ProjectContext] Committed snapshot v${ver}")
    }

    /** Close cleanup shares the writer boundary and never reactivates a hibernated project. */
    void closeDocument(String uri) {
        withWriteLock {
            if (this.@state.get() == ProjectState.DISPOSING) return
            if (this.@visitor == null) {
                this.@visitor = new GrailsASTVisitor(grailsService)
                def previous = snapshotManager.active?.ast
                if (previous instanceof GrailsASTVisitor) this.@visitor.copyFrom((GrailsASTVisitor) previous)
            }
            this.@visitor.removeFileWithDependencies(uri)
            indexManager.evictFile(uri)
            openDocumentUris.remove(TextFile.normalizePath(uri))
            commitSnapshotLocked()
            if (this.@state.get() == ProjectState.HIBERNATED) this.@visitor = null
            grailsService.clearCrossFileCaches()
        }
    }

    /** Permanently removes a deleted document from the project, compiler, visitor, index and snapshots. */
    void deleteDocument(String uri) {
        withWriteLock {
            if (this.@state.get() == ProjectState.DISPOSING) return
            if (this.@compiler != null) {
                this.@compiler.removeSourceFile(uri)
            }
            if (this.@visitor == null) {
                this.@visitor = new GrailsASTVisitor(grailsService)
                def previous = snapshotManager.active?.ast
                if (previous instanceof GrailsASTVisitor) this.@visitor.copyFrom((GrailsASTVisitor) previous)
            }
            this.@visitor.removeFileWithDependencies(uri)
            indexManager.evictFile(uri)
            openDocumentUris.remove(TextFile.normalizePath(uri))
            commitSnapshotLocked()
            if (this.@state.get() == ProjectState.HIBERNATED) this.@visitor = null
            grailsService.clearCrossFileCaches()
        }
    }

    /**
     * Repairs close notifications collapsed by scheduler overload. The scheduler
     * supplies the current tracker view; only previously committed overlays are
     * candidates, so ordinary project sources are never evicted.
     */
    void reconcileClosedDocuments(Set<String> currentOpenUris) {
        Set<String> normalizedOpen = (currentOpenUris ?: Collections.<String>emptySet())
            .collect { String uri -> TextFile.normalizePath(uri) } as Set<String>
        List<String> stale
        astLock.readLock().lock()
        try {
            stale = openDocumentUris.findAll { String uri -> !normalizedOpen.contains(uri) } as List<String>
        } finally {
            astLock.readLock().unlock()
        }
        for (String uri : stale) {
            closeDocument(uri)
            grailsService.fileTracker.clearClosedFileDependencies(uri)
            grailsService.diagnostics.clearDiagnosticsForFile(uri)
        }
    }

    private GradleModel toGradleModel(GrailsProject p) {
        if (!p) return null
        return new GradleModel(
            name: p.name, group: p.group, version: p.version?.toString(),
            grailsVersion: p.grailsVersion, groovyVersion: p.groovyVersion,
            isGrailsProject: p.isGrailsProject, rootDirectory: p.rootDirectory,
            sourceDirectories: p.sourceDirectories ?: [] as Set,
            testDirectories: p.testDirectories ?: [] as Set,
            dependencies: p.dependencies ?: [] as Set,
            sourceSets: p.sourceSets ?: [:]
        )
    }

    // --- Lifecycle Management (Phase 3) ---

    @Override
    SnapshotManager getSnapshotManager() { this.@snapshotManager }

    @Override
    void markAccessed() { this.lastAccessedTime = System.currentTimeMillis() }

    @Override
    long getLastAccessedTime() { this.lastAccessedTime }

    @Override
    ProjectState getState() { this.@state.get() }

    @Override
    CompletableFuture<Void> getActivationFuture() { this.@activationFuture.get() }

    /**
     * Wakes the project from hibernation or failure.
     * Uses atomic compare-and-set on the activationFuture to prevent race conditions
     * causing duplicate GroovyCompiler instances to spawn when multiple concurrent LSP requests arrive.
     *
     * @param activationTask The closure containing the asynchronous compilation logic.
     */
    @Override
    void reactivate(Closure<Void> activationTask) {
        if (this.@state.get() == ProjectState.DISPOSING) return

        CompletableFuture<Void> newFuture = new CompletableFuture<Void>()
        if (this.@activationFuture.compareAndSet(null, newFuture)) {
            this.@state.set(ProjectState.REACTIVATING)
            try {
                activationTask.call()
            } catch (Exception e) {
                this.@state.set(ProjectState.FAILED)
                newFuture.completeExceptionally(e)
                this.@activationFuture.set(null)
            }
        }
    }

    /**
     * Signals the project has successfully finished loading or reactivating.
     * Completing the activation future releases any concurrent LSP threads that were
     * safely blocked waiting for the compiler to become available.
     */
    @Override
    void ready() {
        if (this.@state.get() == ProjectState.DISPOSING) return
        markAccessed() // Prevent immediate LRU eviction
        this.@state.set(ProjectState.READY)
        def future = this.@activationFuture.getAndSet(null)
        if (future != null && !future.isDone()) {
            future.complete(null)
        }
        grailsService.workspaceManager?.enforceMemoryBudget()
    }

    /**
     * Releases expensive AST and compiler memory to prevent OOM errors in large workspaces.
     * Nullifies the compiler, visitor, and classloader ties so the GC can reclaim the
     * heavy Groovy ClassNode trees, while preserving the project metadata and snapshot lineage.
     * If active reader leases are zero, detaches snapshot AST immediately.
     */
    @Override
    void hibernate() {
        if (this.@state.get() == ProjectState.DISPOSING) return
        log.info("[ProjectContext] Hibernating project ${this.@project.name}")
        this.@state.set(ProjectState.HIBERNATED)
        withWriteLock {
            if (this.@compiler != null) this.@compiler.invalidateCompiler()
            if (this.@visitor != null) this.@visitor.invalidateVisitor()
            this.@compiler = null
            this.@visitor = null
        }
        snapshotManager.clearHistory()
        if (activeLeases.get() <= 0) {
            snapshotManager.detachAst()
        }
    }

    /**
     * Terminal state triggered when a workspace folder is removed.
     * Cancels any pending activation futures and clears all references to ensure no
     * background sync or compilation operations leak and run against a deleted project.
     */
    @Override
    void dispose() {
        beginDispose()
        log.info("[ProjectContext] Releasing project ${this.@project.name}")
        withWriteLock {
            if (this.@compiler != null) this.@compiler.invalidateCompiler()
            if (this.@visitor != null) this.@visitor.invalidateVisitor()
            this.@compiler = null
            this.@visitor = null
            openDocumentUris.clear()
        }
        methodScopeCache.clear()
        groovydocCache.clear()
        if (activeLeases.get() <= 0) {
            snapshotManager.clear()
        }
    }

    // --- Request Lease Management (R1-04) ---

    RequestLease acquireLease() {
        activeLeases.incrementAndGet()
        VersionedSnapshot snap = snapshotManager.active
        return new RequestLease(this, snap)
    }

    void releaseLease(RequestLease lease) {
        int remaining = activeLeases.decrementAndGet()
        if (remaining <= 0) {
            checkAndDrain()
        }
    }

    int getActiveLeaseCount() {
        activeLeases.get()
    }

    CompletableFuture<Void> drainLeases(long timeoutMs) {
        CompletableFuture<Void> future = new CompletableFuture<>()
        if (activeLeases.get() <= 0) {
            checkAndDrain()
            future.complete(null)
            return future
        }
        drainListeners.add({ -> future.complete(null) } as Runnable)
        def scheduler = grailsService.workspace?.getOrCreateScheduler()
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.schedule({ ->
                if (!future.isDone()) {
                    checkAndDrain()
                    future.complete(null)
                }
            } as Runnable, timeoutMs, TimeUnit.MILLISECONDS)
        }
        return future
    }

    private void checkAndDrain() {
        ProjectState currentState = this.@state.get()
        if (currentState == ProjectState.HIBERNATED) {
            snapshotManager.detachAst()
        } else if (currentState == ProjectState.DISPOSING) {
            snapshotManager.clear()
        }
        if (activeLeases.get() <= 0) {
            List<Runnable> listeners = new ArrayList<>(drainListeners)
            drainListeners.clear()
            for (Runnable r : listeners) {
                try { r.run() } catch (Exception e) { log.warn("[ProjectContext] Drain listener error", e) }
            }
        }
    }

    /** Makes removal terminal immediately without waiting for the compiler writer lock. */
    void beginDispose() {
        if (this.@state.getAndSet(ProjectState.DISPOSING) == ProjectState.DISPOSING) return
        log.info("[ProjectContext] Disposing project ${this.@project.name}")
        def future = this.@activationFuture.getAndSet(null)
        if (future != null && !future.isDone()) {
            future.cancel(true)
        }
        def syncFuture = this.@currentGradleSyncFuture.getAndSet(null)
        if (syncFuture != null && !syncFuture.isDone()) {
            syncFuture.cancel(true)
        }
        this.@gradleSyncInProgress.set(false)
    }

    /**
     * Ensures the project compiler and visitor are initialized and ready to handle requests.
     * If the project is currently reactivating, the calling thread blocks on the activation future.
     */
    private void ensureActivated() {
        ProjectState currentState = this.@state.get()
        if (currentState == ProjectState.READY) {
            return
        }
        if (currentState == ProjectState.DISPOSING) {
            throw new IllegalStateException("Cannot access compiler on a disposed project context")
        }

        CompletableFuture<Void> future = this.@activationFuture.get()
        if (future != null) {
            try {
                future.join()
            } catch (Exception e) {
                log.error("[ProjectContext] Reactivation failed while waiting", e)
                throw new IllegalStateException("Project reactivation failed", e)
            }
            return
        }

        reactivate {
            log.info("[ProjectContext] Reactivating compiler for project ${this.@project.name}")
            withWriteLock {
                this.@compiler = new GrailsCompiler(grailsService, this)
                this.@visitor = new GrailsASTVisitor(grailsService)
            }
            ready()
        }

        CompletableFuture<Void> postFuture = this.@activationFuture.get()
        if (postFuture != null) {
            try {
                postFuture.join()
            } catch (Exception e) {
                log.error("[ProjectContext] Reactivation failed", e)
                throw new IllegalStateException("Project reactivation failed", e)
            }
        }
    }

    // --- Locking ---

    @Override
    public <T> T withReadLock(groovy.lang.Closure<T> closure) {
        def lock = astLock.readLock()
        lock.lock()
        try { (T) closure.call() } finally { lock.unlock() }
    }

    public <T> T withWriteLock(groovy.lang.Closure<T> closure) {
        def lock = astLock.writeLock()
        lock.lock()
        try { closure.call() } finally { lock.unlock() }
    }

    // --- ProjectContext Interface ---

    @Override GrailsProject getProject() { this.@project }
    @Override Map<String, GrailsProject> getProjects() { [(this.@project.rootDirectory.toURI().toString()): this.@project] }
    @Override String getActiveProjectUri() { this.@project.rootDirectory.toURI().toString() }
    @Override void setActiveProjectUri(String uri) { }
    @Override ProjectDTO getProjectInfo(String projectDir) { ProjectMapper.toDTO(this.@project) }
    @Override void notifyAllProjects() { }
    @Override void addProject(GrailsProject project) { this.@project = project }
    @Override void removeProject(String projectDir) { }
    CompletableFuture<?> getCurrentDiscoveryFuture() {
        return this.@currentDiscoveryFuture.get()
    }

    void applyDependencyUpdate(GrailsProject newProject) {
        if (this.@state.get() == ProjectState.DISPOSING || newProject == null) return
        withWriteLock {
            if (this.@state.get() == ProjectState.DISPOSING) return
            log.info("[ProjectContext] Applying coherent dependency update for: ${newProject.name}")
            this.@project = newProject
            this.@lastSyncError.set(null)
            this.@gradleSyncStale.set(false)
            this.@gradleSyncRetryCount.set(0)

            if (this.@compiler != null) {
                CompletableFuture<?> discFuture = this.@compiler.updateClassLoader()
                this.@currentDiscoveryFuture.set(discFuture)

                // Replay open documents through the updated compiler and index
                if (grailsService.fileTracker != null) {
                    for (String docUri : openDocumentUris) {
                        TextFile tf = grailsService.fileTracker.getTextFile(docUri)
                        if (tf != null && !tf.closed) {
                            try {
                                this.@compiler.compileSourceFile(tf)
                                def su = this.@compiler.getSourceUnit(tf)
                                if (su != null) {
                                    this.@visitor?.visitSourceUnit(su)
                                    def classNodes = su.AST?.classes
                                    if (classNodes != null) {
                                        indexManager.rebuildFile(tf.uri, classNodes)
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("[ProjectContext] Error re-compiling open buffer ${docUri} after dependency update: ${e.message}")
                            }
                        }
                    }
                }
            }

            groovydocCache.clear()
            methodScopeCache.clear()
            grailsService.clearCrossFileCaches()

            commitSnapshotLocked()
        }
        grailsService.workspaceManager?.propagateInvalidation(this)
    }

    @Override void updateProject(GrailsProject p, String projectDir) {
        this.@project = p
        if (p == null) return
        applyDependencyUpdate(p)
        resetFailedState()
    }
    @Override GrailsProject getProjectForUri(String uri) { this.@project }

    @Override
    boolean isDependencyDirty() { dependencyDirty }

    @Override
    void setDependencyDirty(boolean dirty) { dependencyDirty = dirty }

    @Override
    void markDependencyDirty(String originUri, long revision) {
        this.dependencyDirty = true
        if (originUri != null) {
            this.lastInvalidationOrigin.set(originUri)
            this.lastInvalidationRevision.set(revision)
        }
    }

    @Override
    String getLastInvalidationOrigin() {
        return lastInvalidationOrigin.get()
    }

    @Override
    long getLastInvalidationRevision() {
        return lastInvalidationRevision.get()
    }

    @Override
    void resetFailedState() {
        if (this.@state.get() == ProjectState.FAILED) {
            this.@state.set(ProjectState.HIBERNATED)
            this.@activationFuture.set(null)
            this.@gradleSyncRetryCount.set(0)
            this.@lastSyncError.set(null)
            log.info("[ProjectContext] Project ${this.@project.name} reset from FAILED to HIBERNATED")
        }
    }

    @Override
    void triggerGradleSync() {
        triggerGradleSyncInternal(false)
    }

    @Override
    void retryGradleSync() {
        triggerGradleSyncInternal(true)
    }

    @Override
    boolean isGradleSyncInProgress() {
        this.@gradleSyncInProgress.get()
    }

    @Override
    boolean isGradleSyncStale() {
        this.@gradleSyncStale.get()
    }

    @Override
    String getLastSyncError() {
        this.@lastSyncError.get()
    }

    @Override
    int getSyncRetryCount() {
        this.@gradleSyncRetryCount.get()
    }

    @Override
    CompletableFuture<GrailsProject> getCurrentGradleSyncFuture() {
        this.@currentGradleSyncFuture.get()
    }

    private void triggerGradleSyncInternal(boolean isExplicitRetry) {
        if (this.@state.get() == ProjectState.DISPOSING) return

        if (isExplicitRetry) {
            this.@gradleSyncRetryCount.set(0)
        }

        long syncSeq = this.@gradleSyncSequence.incrementAndGet()
        log.info("[ProjectContext] Triggering asynchronous Gradle sync (seq ${syncSeq}) for project: ${this.@project.name}")

        // Cancel any in-flight sync from a superseded generation
        CompletableFuture<GrailsProject> oldFuture = this.@currentGradleSyncFuture.getAndSet(null)
        if (oldFuture != null && !oldFuture.isDone()) {
            log.info("[ProjectContext] Cancelling superseded Gradle sync for project: ${this.@project.name}")
            oldFuture.cancel(true)
        }

        this.@gradleSyncInProgress.set(true)
        this.@gradleSyncStale.set(true)

        // Only block activation if the project has NO usable compiler / snapshot
        // If the project was already READY or HIBERNATED with an LKG snapshot, DO NOT block readers or activation!
        VersionedSnapshot lkg = this.@snapshotManager.getLKG()
        boolean hasUsableState = (this.@state.get() == ProjectState.READY || (lkg != null && lkg.version() > 0))
        boolean isInitialActivation = !hasUsableState && (this.@state.get() == ProjectState.INITIALIZING || this.@state.get() == ProjectState.FAILED)
        CompletableFuture<Void> activationBlocker = null
        if (isInitialActivation) {
            activationBlocker = new CompletableFuture<Void>()
            if (this.@activationFuture.compareAndSet(null, activationBlocker)) {
                this.@state.set(ProjectState.REACTIVATING)
            } else {
                activationBlocker = null
            }
        }

        CompletableFuture<GrailsProject> syncFuture = grailsService.gradle.getGrailsProjectAsync(this.@project.rootDirectory.absolutePath)
        this.@currentGradleSyncFuture.set(syncFuture)

        final CompletableFuture<Void> finalBlocker = activationBlocker

        syncFuture.whenComplete({ GrailsProject newProject, Throwable ex ->
            // If project is disposed, reject late completion
            if (this.@state.get() == ProjectState.DISPOSING) {
                log.info("[ProjectContext] Project ${this.@project?.name} disposed; discarding late Gradle sync (seq ${syncSeq})")
                this.@gradleSyncInProgress.set(false)
                return
            }

            // Check if superseded by a newer sync generation
            if (this.@gradleSyncSequence.get() != syncSeq) {
                log.info("[ProjectContext] Discarding superseded Gradle sync result (seq ${syncSeq}, current is ${this.@gradleSyncSequence.get()}) for project: ${this.@project.name}")
                return
            }

            this.@gradleSyncInProgress.set(false)

            if (ex != null) {
                this.@lastSyncError.set(ex.message ?: ex.getClass().simpleName)
                log.warn("[ProjectContext] Gradle sync failed (seq ${syncSeq}) for project: ${this.@project.name}: ${ex.message}")

                if (hasUsableState) {
                    // Retain usable committed state! Project remains READY (or HIBERNATED).
                    // State remains labeled as gradleSyncStale = true.
                    log.info("[ProjectContext] Preserving usable committed state for ${this.@project.name} despite Gradle sync failure; stale state labeled.")
                    if (finalBlocker != null) {
                        finalBlocker.complete(null)
                        this.@activationFuture.set(null)
                    }
                } else {
                    // No usable state ever existed (initial sync failed)
                    this.@state.set(ProjectState.FAILED)
                    if (finalBlocker != null) {
                        finalBlocker.completeExceptionally(ex)
                        this.@activationFuture.set(null)
                    }
                }

                grailsService.errorService.handleError("Gradle sync failed for project ${this.@project.name}: ${ex.message}", ex, ErrorSource.GRADLE_SERVICE)

                // Bounded retry on transient failure if not explicit retry and retry bound not reached
                int retries = this.@gradleSyncRetryCount.incrementAndGet()
                if (!isExplicitRetry && retries <= MAX_SYNC_RETRIES && this.@state.get() != ProjectState.DISPOSING) {
                    log.info("[ProjectContext] Scheduling bounded retry ${retries}/${MAX_SYNC_RETRIES} for project: ${this.@project.name}")
                    def scheduler = grailsService.workspace?.getOrCreateScheduler()
                    if (scheduler != null && !scheduler.isShutdown()) {
                        scheduler.schedule({ ->
                            if (this.@state.get() != ProjectState.DISPOSING && this.@gradleSyncSequence.get() == syncSeq) {
                                triggerGradleSyncInternal(false)
                            }
                        } as Runnable, 1, TimeUnit.SECONDS)
                    }
                }
            } else if (newProject != null) {
                this.@gradleSyncRetryCount.set(0)
                this.@lastSyncError.set(null)
                this.@gradleSyncStale.set(false)
                this.@project = newProject
                log.info("[ProjectContext] Gradle sync succeeded (seq ${syncSeq}) for project: ${this.@project.name}")
                log.info("[ProjectContext] Gradle sync succeeded (seq ${syncSeq}) for project: ${newProject.name}")

                if (finalBlocker != null) {
                    finalBlocker.complete(null)
                    this.@activationFuture.set(null)
                }

                if (this.@state.get() == ProjectState.INITIALIZING || this.@state.get() == ProjectState.HIBERNATED || this.@state.get() == ProjectState.REACTIVATING) {
                    ready()
                } else if (this.@state.get() == ProjectState.READY) {
                    // Recommit snapshot with updated GradleModel so new dependencies are reflected in snapshot
                    withWriteLock {
                        commitSnapshotLocked()
                    }
                    grailsService.clearCrossFileCaches()
                    grailsService.workspaceManager?.propagateInvalidation(this)
                }
                applyDependencyUpdate(newProject)
            }
        })
    }

    @Override
    void recompileAsync() {
        if (!isRecompilingAsync.compareAndSet(false, true)) {
            log.debug("[ProjectContext] Async recompilation already queued/running for {}", this.@project?.name)
            return
        }
        CompletableFuture.runAsync({ ->
            try {
                if (this.@state.get() == ProjectState.DISPOSING) return
                log.info("[ProjectContext] Asynchronously recompiling project ${this.@project.name} due to dependency changes")
                ensureActivated()

                def allSources = kingsk.grails.lsp.utils.services.ServiceUtils.getAllGroovySourceFilesFromProject(this.project)
                if (allSources) {
                    withWriteLock {
                        allSources.each { file ->
                            String uri = file.toURI().toString()
                            try {
                                String text = file.text
                                TextFile textFile = TextFile.create(uri, text)
                                this.@compiler.markDirty(uri)
                                this.@compiler.compileSourceFile(textFile)
                                def sourceUnit = this.@compiler.getSourceUnit(textFile)
                                if (sourceUnit) {
                                    this.@visitor.visitSourceUnit(sourceUnit)
                                }
                            } catch (Exception fileEx) {
                                log.warn("[ProjectContext] Failed to compile source file ${uri} during async recompilation: ${fileEx.message}")
                            }
                        }
                        commitSnapshotLocked()
                        grailsService.clearCrossFileCaches()
                    }
                    setDependencyDirty(false)
                }
            } catch (Exception e) {
                log.error("[ProjectContext] Async recompilation failed for project ${this.@project.name}", e)
            } finally {
                isRecompilingAsync.set(false)
            }
        } as Runnable)
    }

    // --- CompilationContext Interface ---

    @Override GrailsCompiler getCompiler() {
        this.@compiler
    }
    @Override GrailsASTVisitor getVisitor() {
        this.@visitor
    }
    ClassLoader getClassLoaderUnsafeOrNull() {
        this.@compiler?.classLoader
    }
    @Override ClassLoader getClassLoaderForUri(String uri) {
        this.@compiler?.getClassLoaderForUri(uri)
    }
    @Override FileContentTracker getFileTracker() { grailsService.fileTracker }
    @Override kingsk.grails.lsp.services.ASTService getAstService() { null }
    @Override ProjectIndex getProjectIndex() { projectIndex }
    @Override MethodScopeCache getMethodScopeCache() { methodScopeCache }
    @Override GroovydocCache getGroovydocCache() { groovydocCache }
    @Override GrailsService getGrailsService() { grailsService }
}
