package kingsk.grails.lsp.core.compatibility

import groovy.transform.CompileStatic
import groovy.transform.Immutable

import java.util.List
import java.util.Map

/**
 * Immutable descriptor for an exact verified combination of framework, runtime, toolchain,
 * and pinned artifact dependencies. Conforms to R2-06 and docs/specs/library-discovery.md.
 */
@Immutable
@CompileStatic
class CompatibilityEntry {
    String grailsVersion
    String groovyVersion
    List<Integer> supportedJavaVersions
    List<String> supportedGradleVersions
    Map<String, String> pinnedDependencies
    Map<String, String> artifactFingerprints
    String fixtureProvenance
    CompatibilityLevel level

    boolean matchesExact(String checkGrails, String checkGroovy) {
        if (!checkGrails || !checkGroovy) return false
        return grailsVersion.equalsIgnoreCase(checkGrails.trim()) &&
               groovyVersion.equalsIgnoreCase(checkGroovy.trim())
    }

    boolean isJavaVersionSupported(int javaVersion) {
        return supportedJavaVersions != null && supportedJavaVersions.contains(javaVersion)
    }

    boolean isGradleVersionSupported(String gradleVer) {
        if (!gradleVer || supportedGradleVersions == null) return false
        return supportedGradleVersions.any { gradleVer.startsWith(it) }
    }
}

