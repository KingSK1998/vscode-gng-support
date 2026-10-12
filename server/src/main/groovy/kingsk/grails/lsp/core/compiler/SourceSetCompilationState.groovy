package kingsk.grails.lsp.core.compiler

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import kingsk.grails.lsp.model.types.TextFile
import kingsk.grails.lsp.utils.grails.GrailsUtils
import kingsk.grails.lsp.utils.gsp.GspToGroovyConverter
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.SyntaxException

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

@Slf4j
@CompileStatic
class SourceSetCompilationState {
    final String sourceSetName
    final CompilerConfiguration compilerConfig
    private GroovyClassLoader classLoader
    private URLClassLoader urlClassLoader
    private GrailsCU compilationUnit
    private final Map<String, SourceUnit> sourceUnitsCache = new ConcurrentHashMap<>()
    private final Set<String> dirtySources = ConcurrentHashMap.newKeySet()
    private volatile ErrorCollector cachedErrorCollector
    private volatile int lastClasspathHash = 0
    private final ReentrantLock compileLock = new ReentrantLock()

    SourceSetCompilationState(
        String sourceSetName,
        CompilerConfiguration config,
        List<URL> classpathUrls,
        ClassLoader hostClassLoader
    ) {
        this.sourceSetName = sourceSetName
        this.compilerConfig = config != null ? config : new CompilerConfiguration(CompilerConfiguration.DEFAULT)
        updateClasspath(classpathUrls, hostClassLoader)
    }

    boolean isMain() {
        return "main".equalsIgnoreCase(sourceSetName)
    }

    boolean isTest() {
        return sourceSetName != null && sourceSetName.toLowerCase().contains("test")
    }

    void updateClasspath(List<URL> classpathUrls, ClassLoader hostClassLoader) {
        compileLock.lock()
        try {
            def urls = classpathUrls ?: []
            int hash = urls.hashCode()
            if (hash == lastClasspathHash && classLoader != null) {
                return
            }
            lastClasspathHash = hash

            if (classLoader != null) {
                try {
                    classLoader.close()
                } catch (Exception e) {
                    log.warn("[COMPILER:${sourceSetName}] Error closing old GroovyClassLoader: ${e.message}")
                }
                classLoader = null
            }
            if (urlClassLoader != null) {
                try {
                    urlClassLoader.close()
                } catch (Exception e) {
                    log.warn("[COMPILER:${sourceSetName}] Error closing old URLClassLoader: ${e.message}")
                }
                urlClassLoader = null
            }

            urlClassLoader = new URLClassLoader(
                urls as URL[],
                new IsolatedParentClassLoader(hostClassLoader)
            )
            classLoader = new GroovyClassLoader(urlClassLoader, compilerConfig, true)
            log.info("[COMPILER:${sourceSetName}] Created isolated GroovyClassLoader with ${urls.size()} entries")
            refreshCompilationUnit()
        } finally {
            compileLock.unlock()
        }
    }

    void refreshCompilationUnit() {
        compileLock.lock()
        try {
            compilationUnit = new GrailsCU(compilerConfig, null, classLoader)
            sourceUnitsCache.clear()
            cachedErrorCollector = null
        } finally {
            compileLock.unlock()
        }
    }

    void addSource(File file) {
        if (!file) return
        compileLock.lock()
        try {
            if (classLoader == null || compilationUnit == null) {
                refreshCompilationUnit()
            }
            compilationUnit.addSource(file)
        } finally {
            compileLock.unlock()
        }
    }

    void addSource(String name, String text) {
        if (!name) return
        compileLock.lock()
        try {
            if (classLoader == null || compilationUnit == null) {
                refreshCompilationUnit()
            }
            compilationUnit.addSource(name, text)
        } finally {
            compileLock.unlock()
        }
    }

    void addOrUpdateSource(TextFile file) {
        if (!file) return
        compileLock.lock()
        try {
            if (classLoader == null || compilationUnit == null) {
                refreshCompilationUnit()
            }
            SourceUnit old = compilationUnit.getSourceByName(file.uri)
            if (old != null) {
                compilationUnit.removeSourceUnit(old)
            }
            String compilationText = file.uri.endsWith('.gsp') ?
                GspToGroovyConverter.convertToVirtualGroovy(file.text) :
                file.text
            compilationUnit.addSource(file.uri, compilationText)
        } finally {
            compileLock.unlock()
        }
    }

    void compileSourceFile(TextFile textFile, int targetPhase) {
        if (!textFile) return

        compileLock.lock()
        try {
            addOrUpdateSource(textFile)
            compile(targetPhase)
            clearDirty(textFile.uri)
        } catch (Exception e) {
            log.error("[COMPILER:${sourceSetName}] Compilation failed for ${textFile.name}", e)
        } finally {
            compileLock.unlock()
        }
    }

    boolean compile(int phase) {
        if (phase <= 0 || compilationUnit == null) return true
        compileLock.lock()
        try {
            compilationUnit.clearErrors()
            try {
                compilationUnit.compile(phase)
                if (compilationUnit.errorCollector?.hasErrors()) {
                    recoverFromSyntaxErrors(phase, null)
                }
            } catch (CompilationFailedException e) {
                log.debug("[COMPILER:${sourceSetName}] Compilation failed: ${e.message}")
                recoverFromSyntaxErrors(phase, e)
            } catch (Exception e) {
                log.debug("[COMPILER:${sourceSetName}] Compilation error: ${e.message}")
                recoverFromSyntaxErrors(phase, e)
            } finally {
                updateSourceUnitCache()
            }
            return !compilationUnit?.errorCollector?.hasErrors()
        } finally {
            compileLock.unlock()
        }
    }

    private void recoverFromSyntaxErrors(int phase, Exception e) {
        log.debug("[RECOVERY] Entering recoverFromSyntaxErrors, phase={}, exception={}: {}", phase, e?.class?.name, e?.message)
        if (compilationUnit == null) return
        MultipleCompilationErrorsException mce = (e instanceof MultipleCompilationErrorsException) ?
            (MultipleCompilationErrorsException) e :
            (e?.cause instanceof MultipleCompilationErrorsException ? (MultipleCompilationErrorsException) e.cause : null)

        ErrorCollector preserveError = null
        if (compilationUnit.errorCollector != null) {
            preserveError = new ErrorCollector(compilationUnit.configuration)
            preserveError.addCollectorContents(compilationUnit.errorCollector)
        }
        boolean patchedAny = false

        List<SourceUnit> units = compilationUnit.sourceUnits ?: []

        units.each { SourceUnit sourceUnit ->
            if (sourceUnit.AST != null && !sourceUnit.errorCollector?.hasErrors()) return

            String original = sourceUnit.source.reader.text
            int lineNumber = -1

            def errs = sourceUnit.errorCollector?.errors ?: []
            if (errs.isEmpty()) {
                errs = compilationUnit.errorCollector?.errors ?: []
            }
            if (errs.isEmpty() && mce != null) {
                errs = mce.errorCollector?.errors ?: []
            }

            for (Object err : errs) {
                if (err instanceof SyntaxErrorMessage) {
                    SyntaxException se = ((SyntaxErrorMessage) err).cause
                    if (se != null) {
                        lineNumber = Math.max(se.line - 1, 0)
                        break
                    }
                } else if (err instanceof SyntaxException) {
                    SyntaxException se = (SyntaxException) err
                    lineNumber = Math.max(se.line - 1, 0)
                    break
                }
            }

            List<String> lines = original.readLines()
            if (lineNumber >= 0 && lineNumber < lines.size()) {
                if (!lines[lineNumber].trim().endsWith(".")) {
                    for (int i = lineNumber - 1; i >= Math.max(0, lineNumber - 3); i--) {
                        if (lines[i].trim().endsWith(".")) {
                            lineNumber = i
                            break
                        }
                    }
                }

                if (lineNumber < lines.size()) {
                    String patchText = GrailsUtils.PATTERN_CONSTRUCTOR_CALL.matcher(lines[lineNumber]).matches() ?
                        GrailsUtils.DUMMY_COMPLETION_CONSTRUCTOR : GrailsUtils.DUMMY_COMPLETION_IDENTIFIER

                    lines[lineNumber] += patchText
                    String patchedSource = lines.join("\n")

                    compilationUnit.removeSourceUnit(sourceUnit)
                    compilationUnit.addSource(sourceUnit.name, patchedSource)
                    patchedAny = true
                }
            }
        }

        if (patchedAny) {
            try {
                compilationUnit.clearErrors()
                compilationUnit.compile(phase)
            } catch (Exception recompileEx) {
                log.debug("[RECOVERY] Recompilation failed: {}", recompileEx.message)
                if (compilationUnit.errorCollector != null && preserveError != null) {
                    compilationUnit.errorCollector.addCollectorContents(preserveError)
                }
            }
        } else {
            if (compilationUnit.errorCollector != null && preserveError != null) {
                compilationUnit.errorCollector.addCollectorContents(preserveError)
            }
        }
    }

    private void updateSourceUnitCache() {
        if (compilationUnit == null) return
        compilationUnit.iterator().forEachRemaining { sourceUnit ->
            def uri = TextFile.normalizePath(sourceUnit.name)
            sourceUnitsCache[uri] = sourceUnit
        }
    }

    SourceUnit getSourceUnit(TextFile textFile) {
        if (!textFile?.uri) return null
        SourceUnit direct = sourceUnitsCache.get(textFile.uri)
        if (direct != null) return direct
        String norm = TextFile.normalizePath(textFile.uri)
        return (norm && norm != textFile.uri) ? sourceUnitsCache.get(norm) : null
    }

    SourceUnit getSourceUnit(String uri) {
        if (!uri) return null
        SourceUnit direct = sourceUnitsCache.get(uri)
        if (direct != null) return direct
        String norm = TextFile.normalizePath(uri)
        return (norm && norm != uri) ? sourceUnitsCache.get(norm) : null
    }

    List<SourceUnit> getSourceUnits() {
        return sourceUnitsCache.values() as List<SourceUnit>
    }

    boolean compilationExistsFor(String uri) {
        if (!uri) return false
        if (sourceUnitsCache.containsKey(uri)) return true
        String norm = TextFile.normalizePath(uri)
        return (norm && norm != uri) ? sourceUnitsCache.containsKey(norm) : false
    }

    void removeSourceFile(String uri) {
        if (!uri) return
        dirtySources.remove(uri)
        String norm = TextFile.normalizePath(uri)
        if (norm && norm != uri) {
            dirtySources.remove(norm)
        }
        SourceUnit old = sourceUnitsCache.remove(uri)
        if (old == null && norm && norm != uri) {
            old = sourceUnitsCache.remove(norm)
        }
        if (old != null && compilationUnit != null) {
            compilationUnit.removeSourceUnit(old)
        }
    }

    void markDirty(String uri) {
        if (uri) dirtySources.add(uri)
    }

    boolean isDirty(String uri) {
        uri ? dirtySources.contains(uri) : false
    }

    void clearDirty(String uri) {
        if (uri) dirtySources.remove(uri)
    }

    void clearAllDirty() {
        dirtySources.clear()
    }

    int getDirtyCount() {
        dirtySources.size()
    }

    void invalidate() {
        compileLock.lock()
        try {
            sourceUnitsCache.clear()
            cachedErrorCollector = null
            dirtySources.clear()
            if (compilationUnit != null) {
                try {
                    compilationUnit.clearErrors()
                } catch (Exception ignored) {}
                compilationUnit = null
            }
            if (classLoader != null) {
                try {
                    classLoader.close()
                } catch (Exception e) {
                    log.warn("[COMPILER:${sourceSetName}] Error closing classloader: ${e.message}")
                }
                classLoader = null
            }
            if (urlClassLoader != null) {
                try {
                    urlClassLoader.close()
                } catch (Exception e) {
                    log.warn("[COMPILER:${sourceSetName}] Error closing URLClassLoader: ${e.message}")
                }
                urlClassLoader = null
            }
            lastClasspathHash = 0
        } finally {
            compileLock.unlock()
        }
    }

    GroovyClassLoader getClassLoader() {
        classLoader
    }

    GrailsCU getCompilationUnit() {
        compilationUnit
    }

    void setCompilationUnit(GrailsCU cu) {
        this.compilationUnit = cu
    }

    ErrorCollector getErrorCollectorOrNull() {
        if (compilationUnit?.errorCollector != cachedErrorCollector) {
            cachedErrorCollector = compilationUnit?.errorCollector
        }
        cachedErrorCollector
    }
}
