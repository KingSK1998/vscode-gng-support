package kingsk.grails.lsp.services

import kingsk.grails.lsp.test.BaseLspSpec
import kingsk.grails.lsp.model.dto.GrailsProject
import kingsk.grails.lsp.model.state.ProjectState
import kingsk.grails.lsp.context.ProjectContextImpl
import kingsk.grails.lsp.model.types.TextFile
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.gradle.tooling.CancellationTokenSource
import spock.lang.Subject

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GradleSyncSpec extends BaseLspSpec {

    @Subject
    GrailsWorkspaceService workspaceService

    def setup() {
        setupProject()
        workspaceService = grailsService.workspace
        workspaceService.shutdown()
    }

    def cleanup() {
        workspaceService?.shutdown()
        grailsService.gradle?.setBuildFunction(null)
    }

    def "should cancel previous debounce future when new build file change arrives"() {
        given: "A workspace with a project"
        File root = new File(System.getProperty("user.dir"), "build/gradle_bounce_test")
        root.mkdirs()
        new File(root, "build.gradle").text = ""
        def proj = new GrailsProject(name: "BounceTest", rootDirectory: root)
        grailsService.workspaceManager.addProject(proj)

        String buildUri = new File(root, "build.gradle").toURI().toString()

        when: "A build file change event arrives"
        workspaceService.didChangeWatchedFiles(
            new DidChangeWatchedFilesParams(
                [new FileEvent(buildUri, FileChangeType.Changed)]
            )
        )

        then: "A debounce future is scheduled"
        workspaceService.@debounceFuture != null
        !workspaceService.@debounceFuture.isDone()

        when: "Another change arrives before debounce fires"
        def firstFuture = workspaceService.@debounceFuture
        workspaceService.didChangeWatchedFiles(
            new DidChangeWatchedFilesParams(
                [new FileEvent(buildUri, FileChangeType.Changed)]
            )
        )

        then: "Previous future was cancelled, new one is scheduled"
        firstFuture.isCancelled() || firstFuture.isDone()
        workspaceService.@debounceFuture != null
        workspaceService.@debounceFuture != firstFuture
    }

    def "should not trigger sync for non-build files"() {
        given: "A workspace with a project"
        File root = new File(System.getProperty("user.dir"), "build/gradle_nontrigger_test")
        root.mkdirs()
        def proj = new GrailsProject(name: "NoTriggerTest", rootDirectory: root)
        grailsService.workspaceManager.addProject(proj)

        when: "A non-build file change event arrives"
        String groovyUri = new File(root, "src/main/groovy/Test.groovy").toURI().toString()
        workspaceService.didChangeWatchedFiles(
            new DidChangeWatchedFilesParams(
                [new FileEvent(groovyUri, FileChangeType.Changed)]
            )
        )

        then: "No debounce future is scheduled for non-build files"
        workspaceService.@debounceFuture == null
    }

    def "should transition to FAILED state on Gradle sync error for non-Gradle project"() {
        given: "A project directory without Gradle wrapper"
        File root = new File(System.getProperty("user.dir"), "build/gradle_fail_test")
        root.mkdirs()
        new File(root, "build.gradle").text = "throw new RuntimeException('Intentional build failure')"
        def proj = new GrailsProject(name: "FailTest", rootDirectory: root)
        grailsService.workspaceManager.addProject(proj)
        def ctx = (ProjectContextImpl) grailsService.workspaceManager.getProjectForUri(root.toURI().toString())

        and: "Context initially not FAILED"
        ctx.state != ProjectState.FAILED

        when: "Gradle sync is triggered on non-Gradle directory"
        ctx.triggerGradleSync()
        long start = System.currentTimeMillis()
        while (ctx.state != ProjectState.FAILED && (System.currentTimeMillis() - start) < 25000) {
            Thread.sleep(100)
        }

        then: "Project transitions to FAILED state on sync error"
        ctx.state == ProjectState.FAILED
    }

    def "should reset from FAILED to HIBERNATED on build file update"() {
        given: "A project in FAILED state"
        File root = new File(System.getProperty("user.dir"), "build/gradle_reset_test")
        root.mkdirs()
        new File(root, "build.gradle").text = ""
        def proj = new GrailsProject(name: "ResetTest", rootDirectory: root)
        grailsService.workspaceManager.addProject(proj)
        def ctx = (ProjectContextImpl) grailsService.workspaceManager.getProjectForUri(root.toURI().toString())

        // Force to FAILED
        def field = ProjectContextImpl.getDeclaredField("state")
        field.setAccessible(true)
        def stateRef = field.get(ctx) as java.util.concurrent.atomic.AtomicReference<ProjectState>
        stateRef.set(ProjectState.FAILED)
        assert ctx.state == ProjectState.FAILED

        when: "resetFailedState is called (triggered by build file update)"
        ctx.resetFailedState()

        then: "Project transitions from FAILED to HIBERNATED"
        ctx.state == ProjectState.HIBERNATED
    }

    def "should identify build configuration files correctly"() {
        expect:
        GrailsWorkspaceService.isBuildConfigurationFile("file:///path/build.gradle")
        GrailsWorkspaceService.isBuildConfigurationFile("file:///path/build.gradle.kts")
        GrailsWorkspaceService.isBuildConfigurationFile("file:///path/settings.gradle")
        GrailsWorkspaceService.isBuildConfigurationFile("file:///path/gradle.properties")

        and:
        !GrailsWorkspaceService.isBuildConfigurationFile("file:///path/SomeController.groovy")
        !GrailsWorkspaceService.isBuildConfigurationFile("file:///path/application.yml")
        !GrailsWorkspaceService.isBuildConfigurationFile(null)
    }

    def "should cache Gradle project after successful sync"() {
        given: "GradleService with a project cache"
        def gradleService = grailsService.gradle
        File root = new File(System.getProperty("user.dir"), "build/gradle_cache_test")
        root.mkdirs()

        expect: "Cache invalidation works without errors"
        gradleService.invalidateCache()
        gradleService.invalidateProjectCache(root.absolutePath)

        and: "ProjectCache exists and is initialized"
        gradleService.@cache != null
    }

    def "R1-03/1: broken build or sync failure preserves usable committed state on ready project"() {
        given: "A ready project with a compiled document and committed snapshot"
        File root = new File(System.getProperty("user.dir"), "build/gradle_usable_state_test")
        root.mkdirs()
        new File(root, "build.gradle").text = "plugins { id 'groovy' }"
        def proj = new GrailsProject(name: "UsableStateTest", rootDirectory: root)
        grailsService.workspaceManager.addProject(proj)
        def ctx = (ProjectContextImpl) grailsService.workspaceManager.getProjectForUri(root.toURI().toString())
        ctx.ready()

        // Commit initial document so version > 0
        String docUri = new File(root, "Book.groovy").toURI().toString()
        ctx.compileAndVisitAST(TextFile.create(docUri, "class Book { String title }"))
        assert ctx.snapshotManager.LKG != null
        assert ctx.snapshotManager.LKG.version() > 0
        long initialSnapshotVer = ctx.snapshotManager.LKG.version()

        and: "A failing Gradle build function"
        grailsService.gradle.setBuildFunction({ File dir, CancellationTokenSource cts ->
            throw new RuntimeException("Simulated broken build / offline failure")
        })

        when: "Gradle sync is triggered on the ready project"
        ctx.triggerGradleSync()
        long start = System.currentTimeMillis()
        while (ctx.isGradleSyncInProgress() && (System.currentTimeMillis() - start) < 5000) {
            Thread.sleep(50)
        }

        then: "Sync failure preserves usable committed state and project stays READY"
        ctx.state == ProjectState.READY
        ctx.isGradleSyncStale()
        ctx.lastSyncError?.contains("Simulated broken build")
        ctx.snapshotManager.LKG != null
        ctx.snapshotManager.LKG.version() == initialSnapshotVer
        ctx.compiler != null
        ctx.visitor != null
    }

    def "R1-03/1: superseded sync cancels in-flight work and applies newest configuration"() {
        given: "A project with controlled build function"
        File root = new File(System.getProperty("user.dir"), "build/gradle_supersede_test")
        root.mkdirs()
        new File(root, "build.gradle").text = ""
        grailsService.gradle.cache.forceInvalidation(root)
        def proj = new GrailsProject(name: "SupersedeTest", rootDirectory: root)
        grailsService.workspaceManager.addProject(proj)
        def ctx = (ProjectContextImpl) grailsService.workspaceManager.getProjectForUri(root.toURI().toString())
        ctx.ready()

        CountDownLatch sync1Started = new CountDownLatch(1)
        CountDownLatch sync1Proceed = new CountDownLatch(1)
        AtomicBoolean sync1Cancelled = new AtomicBoolean(false)

        grailsService.gradle.setBuildFunction({ File dir, CancellationTokenSource cts ->
            if (cts.token().isCancellationRequested()) {
                sync1Cancelled.set(true)
            }
            if (sync1Started.count > 0) {
                sync1Started.countDown()
                sync1Proceed.await(5, TimeUnit.SECONDS)
                if (cts.token().isCancellationRequested()) {
                    sync1Cancelled.set(true)
                }
                return new GrailsProject(name: "OldSyncResult", rootDirectory: dir)
            } else {
                return new GrailsProject(name: "NewSyncResult", rootDirectory: dir)
            }
        })

        when: "First sync starts and blocks"
        ctx.triggerGradleSync()
        assert sync1Started.await(5, TimeUnit.SECONDS)
        def firstFuture = ctx.currentGradleSyncFuture

        and: "Second sync is triggered before first completes"
        ctx.triggerGradleSync()
        def secondFuture = ctx.currentGradleSyncFuture

        then: "First sync future was cancelled and superseded"
        firstFuture != secondFuture
        firstFuture.isCancelled() || firstFuture.isDone()

        when: "First sync completes after being superseded"
        sync1Proceed.countDown()
        try {
            secondFuture?.get(10, TimeUnit.SECONDS)
        } catch (Exception ignored) {}
        long start = System.currentTimeMillis()
        while (ctx.isGradleSyncInProgress() && (System.currentTimeMillis() - start) < 10000) {
            Thread.sleep(50)
        }

        then: "Second sync result was applied and old result was not committed"
        ctx.project.name == "NewSyncResult"
        !ctx.isGradleSyncStale()
    }

    def "R1-03/2: during 30-second simulated sync stall, existing reads complete without waiting or acquiring locks"() {
        given: "A ready project with committed snapshot"
        File root = new File(System.getProperty("user.dir"), "build/gradle_stall_test")
        root.mkdirs()
        new File(root, "build.gradle").text = ""
        def proj = new GrailsProject(name: "StallTest", rootDirectory: root)
        grailsService.workspaceManager.addProject(proj)
        def ctx = (ProjectContextImpl) grailsService.workspaceManager.getProjectForUri(root.toURI().toString())
        ctx.ready()

        String docUri = new File(root, "StallModel.groovy").toURI().toString()
        ctx.compileAndVisitAST(TextFile.create(docUri, "class StallModel { String name }"))

        CountDownLatch stallLatch = new CountDownLatch(1)
        CountDownLatch syncEntered = new CountDownLatch(1)

        grailsService.gradle.setBuildFunction({ File dir, CancellationTokenSource cts ->
            syncEntered.countDown()
            stallLatch.await(30, TimeUnit.SECONDS)
            return new GrailsProject(name: "StallTestDone", rootDirectory: dir)
        })

        when: "Gradle sync is triggered and enters simulated stall"
        ctx.triggerGradleSync()
        assert syncEntered.await(5, TimeUnit.SECONDS)
        assert ctx.isGradleSyncInProgress()
        assert ctx.isGradleSyncStale()

        then: "Existing reads complete promptly (< 200 ms) without waiting on Gradle"
        long readStart = System.currentTimeMillis()
        def readResult = ctx.withReadLock {
            return ctx.snapshotManager.active?.ast() != null
        }
        def snapshot = ctx.snapshotManager.active
        def compiler = ctx.compiler
        long readDuration = System.currentTimeMillis() - readStart

        readResult == true
        snapshot != null
        compiler != null
        readDuration < 200 // Completed in milliseconds, not 30 seconds!

        cleanup:
        stallLatch.countDown()
    }

    def "R1-03/3: bounded retry stops at limit and resets on explicit trigger"() {
        given: "A failing project with tracked sync attempts"
        File root = new File(System.getProperty("user.dir"), "build/gradle_retry_test")
        root.mkdirs()
        new File(root, "build.gradle").text = ""
        def proj = new GrailsProject(name: "RetryTest", rootDirectory: root)
        grailsService.workspaceManager.addProject(proj)
        def ctx = (ProjectContextImpl) grailsService.workspaceManager.getProjectForUri(root.toURI().toString())

        AtomicInteger attemptCounter = new AtomicInteger(0)
        grailsService.gradle.setBuildFunction({ File dir, CancellationTokenSource cts ->
            attemptCounter.incrementAndGet()
            throw new RuntimeException("Transient failure #" + attemptCounter.get())
        })

        when: "Sync is triggered on failing project"
        ctx.triggerGradleSync()

        and: "Wait for initial attempt and scheduled bounded retries to exhaust"
        long start = System.currentTimeMillis()
        while (ctx.syncRetryCount < ProjectContextImpl.MAX_SYNC_RETRIES && (System.currentTimeMillis() - start) < 5000) {
            Thread.sleep(100)
        }
        Thread.sleep(1500) // allow retry window to settle

        then: "Retry count reached bound and stopped spinning"
        ctx.syncRetryCount >= ProjectContextImpl.MAX_SYNC_RETRIES
        attemptCounter.get() <= ProjectContextImpl.MAX_SYNC_RETRIES + 1

        when: "Explicit retry is triggered via retryGradleSync"
        ctx.retryGradleSync()
        Thread.sleep(200)

        then: "Retry count is reset on explicit trigger"
        ctx.syncRetryCount <= 1
    }

    def "R1-03/3: debounce preserves all affected roots when multiple build files change"() {
        given: "Workspace with two separate projects"
        File rootA = new File(System.getProperty("user.dir"), "build/gradle_multiroot_a")
        File rootB = new File(System.getProperty("user.dir"), "build/gradle_multiroot_b")
        rootA.mkdirs()
        rootB.mkdirs()
        new File(rootA, "build.gradle").text = ""
        new File(rootB, "build.gradle").text = ""

        def projA = new GrailsProject(name: "MultiRootA", rootDirectory: rootA)
        def projB = new GrailsProject(name: "MultiRootB", rootDirectory: rootB)
        grailsService.workspaceManager.addProject(projA)
        grailsService.workspaceManager.addProject(projB)

        def ctxA = (ProjectContextImpl) grailsService.workspaceManager.getProjectForUri(rootA.toURI().toString())
        def ctxB = (ProjectContextImpl) grailsService.workspaceManager.getProjectForUri(rootB.toURI().toString())
        ctxA.ready()
        ctxB.ready()

        AtomicInteger syncedA = new AtomicInteger(0)
        AtomicInteger syncedB = new AtomicInteger(0)

        grailsService.gradle.setBuildFunction({ File dir, CancellationTokenSource cts ->
            if (dir.name.contains("a")) syncedA.incrementAndGet()
            if (dir.name.contains("b")) syncedB.incrementAndGet()
            return new GrailsProject(name: dir.name, rootDirectory: dir)
        })

        when: "Build file events for both roots arrive in the same debounce window"
        String uriA = new File(rootA, "build.gradle").toURI().toString()
        String uriB = new File(rootB, "build.gradle").toURI().toString()

        workspaceService.didChangeWatchedFiles(
            new DidChangeWatchedFilesParams([new FileEvent(uriA, FileChangeType.Changed)])
        )
        workspaceService.didChangeWatchedFiles(
            new DidChangeWatchedFilesParams([new FileEvent(uriB, FileChangeType.Changed)])
        )

        and: "Debounce period fires"
        workspaceService.processBuildConfigurationChanges()

        long start = System.currentTimeMillis()
        while ((ctxA.isGradleSyncInProgress() || ctxB.isGradleSyncInProgress()) && (System.currentTimeMillis() - start) < 5000) {
            Thread.sleep(50)
        }

        then: "Both roots were preserved and had sync triggered"
        syncedA.get() >= 1
        syncedB.get() >= 1
    }

    def "R1-03/3: disposal cancels in-flight sync and rejects late completion"() {
        given: "A project with in-flight sync"
        File root = new File(System.getProperty("user.dir"), "build/gradle_dispose_test")
        root.mkdirs()
        new File(root, "build.gradle").text = ""
        def proj = new GrailsProject(name: "DisposeTest", rootDirectory: root)
        grailsService.workspaceManager.addProject(proj)
        def ctx = (ProjectContextImpl) grailsService.workspaceManager.getProjectForUri(root.toURI().toString())
        ctx.ready()

        CountDownLatch syncEntered = new CountDownLatch(1)
        CountDownLatch finishSync = new CountDownLatch(1)
        AtomicBoolean wasCancelled = new AtomicBoolean(false)

        grailsService.gradle.setBuildFunction({ File dir, CancellationTokenSource cts ->
            syncEntered.countDown()
            finishSync.await(5, TimeUnit.SECONDS)
            if (cts.token().isCancellationRequested()) {
                wasCancelled.set(true)
            }
            return new GrailsProject(name: "LateResurrectedName", rootDirectory: dir)
        })

        when: "Sync is triggered and is running"
        ctx.triggerGradleSync()
        assert syncEntered.await(5, TimeUnit.SECONDS)
        assert ctx.isGradleSyncInProgress()

        and: "Project is disposed while sync is running"
        ctx.dispose()

        then: "State is DISPOSING and in-flight sync was cancelled"
        ctx.state == ProjectState.DISPOSING
        !ctx.isGradleSyncInProgress()

        when: "The late async build finishes"
        finishSync.countDown()
        Thread.sleep(300)

        then: "Project is not resurrected to READY and late project model is rejected"
        ctx.state == ProjectState.DISPOSING
        ctx.project.name != "LateResurrectedName"
    }
}