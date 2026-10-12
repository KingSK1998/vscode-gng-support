package kingsk.grails.lsp.services

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import kingsk.grails.lsp.GrailsService
import kingsk.grails.lsp.core.gradle.GrailsProjectBuilder
import kingsk.grails.lsp.core.gradle.ProjectCache
import kingsk.grails.lsp.model.dto.DependencyNode
import kingsk.grails.lsp.model.enums.ErrorSource
import kingsk.grails.lsp.model.dto.GrailsProject
import kingsk.grails.lsp.protocol.mapper.ProjectMapper

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.gradle.tooling.CancellationTokenSource
import org.gradle.tooling.GradleConnector

@Slf4j
@CompileStatic
class GradleService {
    private final GrailsService service
    private final ProjectCache cache = new ProjectCache()
    private final GrailsProjectBuilder builder = new GrailsProjectBuilder()
    private java.util.function.BiFunction<File, CancellationTokenSource, GrailsProject> buildFunction = { File dir, CancellationTokenSource cts ->
        builder.build(dir, cts)
    }

    GradleService(GrailsService service) {
        this.service = service
    }

    void setBuildFunction(java.util.function.BiFunction<File, CancellationTokenSource, GrailsProject> fn) {
        this.buildFunction = fn
    }

    java.util.function.BiFunction<File, CancellationTokenSource, GrailsProject> getBuildFunction() {
        return this.buildFunction
    }

    static File resolveProjectDir(String projectDir) {
        if (!projectDir) return null
        if (projectDir.startsWith("file:")) {
            try {
                return new File(new URI(projectDir))
            } catch (Exception e) {
                try {
                    return new File(URI.create(projectDir.replace(" ", "%20")))
                } catch (Exception ignored) {
                    return new File(kingsk.grails.lsp.model.types.TextFile.normalizePath(projectDir))
                }
            }
        }
        return new File(projectDir)
    }

    /**
     * Asynchronously builds a GrailsProject via the Gradle Tooling API.
     * Features: 30-second timeout, explicit cancellation, and per-call connection ownership.
     * The CancellationTokenSource is always cancelled on failure to prevent daemon leaks.
     * The CancellationTokenSource is always cancelled on failure or cancellation to prevent daemon leaks.
     */
    CompletableFuture<GrailsProject> getGrailsProjectAsync(String projectDir) {
        CancellationTokenSource cancellationSource = GradleConnector.newCancellationTokenSource()

        CompletableFuture<GrailsProject> future = CompletableFuture.supplyAsync({ ->
            try {
                File rootDir = resolveProjectDir(projectDir)
                if (rootDir == null || !rootDir.exists()) {
                    throw new FileNotFoundException("[GRADLE] Project directory does not exist: ${projectDir}")
                }

                if (buildFunction == null && !cache.isStale(rootDir)) {
                    GrailsProject cached = cache.load(rootDir)
                    if (cached) {
                        return cached
                    }
                }

                log.info("[GRADLE] Building GrailsProject via Tooling API with 30s timeout: ${rootDir.name}")
                GrailsProject project = (buildFunction != null) ? buildFunction.apply(rootDir, cancellationSource) : builder.build(rootDir, cancellationSource)
                if (cancellationSource.token().isCancellationRequested()) {
                    log.info("[GRADLE] Gradle sync cancelled for ${rootDir.name}; discarding result and skipping cache")
                    throw new java.util.concurrent.CancellationException("Gradle sync cancelled")
                }
                if (buildFunction == null) {
                    cache.save(rootDir, project)
                }

                service.client?.projectUpdated(ProjectMapper.toDTO(project))
                return project
            } catch (Exception e) {
                log.error("[GRADLE] Gradle sync failed: ${projectDir}", e)
                throw e
            }
        } as java.util.function.Supplier<GrailsProject>)

        CompletableFuture<GrailsProject> timeoutFuture = future.orTimeout(30, TimeUnit.SECONDS)
        timeoutFuture.whenComplete({ GrailsProject res, Throwable ex ->
            if (ex != null) {
                if (ex instanceof java.util.concurrent.TimeoutException || ex?.cause instanceof java.util.concurrent.TimeoutException) {
                    log.warn("[GRADLE] Gradle sync timed out after 30 seconds for: ${projectDir}. Triggering cancellation.")
                } else if (ex instanceof java.util.concurrent.CancellationException || ex?.cause instanceof java.util.concurrent.CancellationException) {
                    log.info("[GRADLE] Gradle sync cancelled for: ${projectDir}.")
                } else {
                    log.warn("[GRADLE] Gradle sync failed for: ${projectDir}. Triggering cancellation to prevent daemon leak.")
                }
                cancellationSource.cancel()
                future.cancel(true)
            }
        })
        return timeoutFuture
    }

    GrailsProject getGrailsProject(String projectDir) {
        return getGrailsProjectSync(projectDir)
    }

    private GrailsProject getGrailsProjectSync(String projectDir) {
        File rootDir = resolveProjectDir(projectDir)
        if (rootDir == null || !rootDir.exists()) {
            throw new FileNotFoundException("[GRADLE] Project directory does not exist: ${projectDir}")
        }

        // Try to load from cache first (only if not stale)
        if (!cache.isStale(rootDir)) {
            GrailsProject cached = cache.load(rootDir)
            if (cached) {
                log.debug("[GRADLE] Project loaded from cache: ${rootDir.name}")
                return cached
            }
            // If cache exists but load returns null (corrupted), fall through to rebuild
            log.debug("[GRADLE] Cache file exists but corrupted, rebuilding: ${rootDir.name}")
        }

        // Build project once (cache is stale, missing, or corrupted)
        log.info("[GRADLE] Building GrailsProject from Gradle API for: ${rootDir.name}")
        long startTime = System.currentTimeMillis()

        try {
            GrailsProject project = builder.build(rootDir)
            cache.save(rootDir, project)

            // notify client about the new project
            service.client?.projectUpdated(ProjectMapper.toDTO(project))

            long buildTime = System.currentTimeMillis() - startTime
            long cacheSize = cache.getCacheSize(rootDir)
            log.info("[GRADLE] GrailsProject built and cached in ${buildTime}ms: ${cacheSize / 1024}KB")
            return project
        } catch (Exception e) {
            service.errorService.handleError("Failed to build project from Gradle API for: ${rootDir.name}", e, ErrorSource.GRADLE_SERVICE)
            throw new IllegalStateException("Failed to build project: ${rootDir.absolutePath}", e)
        }
    }

    void invalidateCache() {
        cache.bumpUpVersion()
        log.info("[GRADLE] ProjectInfoCache version bumped to: ${cache.currentVersion}")
    }

    /**
     * Force invalidate cache for a specific project
     */
    void invalidateProjectCache(String projectDir) {
        File rootDir = resolveProjectDir(projectDir)
        if (rootDir != null) {
            cache.forceInvalidation(rootDir)
            log.info("[GRADLE] Force invalidated cache for project: ${rootDir.name}")
        }
    }

    // ===== Download Javadoc or Sources =====

    /**
     * Download javadoc artifact
     */
    File downloadJavaDocJarFile(File rootDirectory, DependencyNode dependency) {
        return builder.downloadArtifactInternal(rootDirectory, dependency, 'javadoc')
    }

    /**
     * Download sources artifact
     */
    File downloadSourcesJarFile(File rootDirectory, DependencyNode dependency) {
        return builder.downloadArtifactInternal(rootDirectory, dependency, 'sources')
    }
}