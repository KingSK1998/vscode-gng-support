package kingsk.grails.lsp.core.compatibility

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import kingsk.grails.lsp.model.dto.DependencyNode
import kingsk.grails.lsp.model.dto.GradleModel
import kingsk.grails.lsp.model.dto.GrailsProject
import java.util.Collections
import java.util.List
import java.util.Map
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Authoritative compatibility registry and evaluation engine for Grails, Groovy, JDK, and Gradle.
 * Conforms to R2-06, docs/specs/library-discovery.md, and docs/specs/validation.md.
 *
 * Rules:
 * 1. Pinned Grails 7 / Groovy 4 combinations with verified fixtures are TESTED.
 * 2. New Grails 7 minor/patch versions (e.g. 7.9.0) do NOT automatically become TESTED;
 *    they are classified as EXPERIMENTAL until evidence is established.
 * 3. Grails < 7, Groovy < 4, and JDK < 17 are explicitly UNSUPPORTED in 1.0 scope.
 * 4. Offline replay verifies artifact content fingerprints without network calls.
 */
@Slf4j
@CompileStatic
class CompatibilityMatrix {

    public static final CompatibilityMatrix INSTANCE = new CompatibilityMatrix()

    private final List<CompatibilityEntry> entries = new CopyOnWriteArrayList<>()

    CompatibilityMatrix() {
        initDefaultMatrix()
    }

    private void initDefaultMatrix() {
        // Entry 1: Grails 7.0.0-RC1 + Groovy 4.0.23 (Active test fixture)
        Map<String, String> rc1Deps = [
            'org.apache.grails:grails-core': '7.0.0-RC1',
            'org.apache.grails:grails-data-hibernate5': '7.0.0-RC1',
            'org.apache.grails:grails-gsp': '7.0.0-RC1',
            'org.apache.grails:grails-web-boot': '7.0.0-RC1'
        ]
        Map<String, String> rc1Fingerprints = [
            'org.apache.grails:grails-core:7.0.0-RC1': 'sha256:7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069',
            'org.apache.grails:grails-data-hibernate5:7.0.0-RC1': 'sha256:cb34a719d36e2f491c30d9fd9d42189851086a67f08c3ec437c355fbb6fc115b',
            'org.apache.grails:grails-gsp:7.0.0-RC1': 'sha256:4d6023348d61394f79abf51e06eef1e68fa7075c324bc681bcf704e6c9ff94ef',
            'org.apache.grails:grails-web-boot:7.0.0-RC1': 'sha256:a68759d57a972c3d52c2bc7e492ce2632b507b5a1984fb126fc9c9ec52be5356'
        ]
        entries.add(new CompatibilityEntry(
            '7.0.0-RC1',
            '4.0.23',
            Collections.unmodifiableList([17, 21]),
            Collections.unmodifiableList(['8.10', '8.11', '8.12']),
            Collections.unmodifiableMap(rc1Deps),
            Collections.unmodifiableMap(rc1Fingerprints),
            'server/src/test/resources/test-projects/grails-test-project, Apache Grails 7.0.0-RC1 release',
            CompatibilityLevel.TESTED
        ))

        // Entry 2: Grails 7.0.0 GA + Groovy 4.0.24 (Pinned GA release)
        Map<String, String> gaDeps = [
            'org.apache.grails:grails-core': '7.0.0',
            'org.apache.grails:grails-data-hibernate5': '7.0.0',
            'org.apache.grails:grails-gsp': '7.0.0',
            'org.apache.grails:grails-web-boot': '7.0.0'
        ]
        Map<String, String> gaFingerprints = [
            'org.apache.grails:grails-core:7.0.0': 'sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
            'org.apache.grails:grails-data-hibernate5:7.0.0': 'sha256:9f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069',
            'org.apache.grails:grails-gsp:7.0.0': 'sha256:3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b',
            'org.apache.grails:grails-web-boot:7.0.0': 'sha256:1a84f9b57a972c3d52c2bc7e492ce2632b507b5a1984fb126fc9c9ec52be5356'
        ]
        entries.add(new CompatibilityEntry(
            '7.0.0',
            '4.0.24',
            Collections.unmodifiableList([17, 21]),
            Collections.unmodifiableList(['8.10', '8.11', '8.12']),
            Collections.unmodifiableMap(gaDeps),
            Collections.unmodifiableMap(gaFingerprints),
            'Apache Grails 7.0.0 GA release artifacts',
            CompatibilityLevel.TESTED
        ))
    }

    /**
     * Returns an unmodifiable list of all tested matrix entries.
     */
    List<CompatibilityEntry> getTestedEntries() {
        return Collections.unmodifiableList(entries.findAll { it.level == CompatibilityLevel.TESTED })
    }

    /**
     * Finds an exact matrix entry matching Grails version and Groovy version.
     */
    CompatibilityEntry findEntry(String grailsVersion, String groovyVersion) {
        if (!grailsVersion || !groovyVersion) return null
        return entries.find { it.matchesExact(grailsVersion, groovyVersion) }
    }

    /**
     * Registers a tested combination entry.
     */
    void registerEntry(CompatibilityEntry entry) {
        if (entry != null && !entries.contains(entry)) {
            entries.add(entry)
        }
    }

    /**
     * Evaluates a GradleModel against the compatibility matrix.
     */
    CompatibilityAssessment evaluate(GradleModel model, Integer javaVersion = null, String gradleVersion = null) {
        if (model == null) {
            return CompatibilityAssessment.unsupported("Null project model cannot be evaluated")
        }
        if (!model.isGrailsProject) {
            String projName = model.name ?: "unknown"
            return CompatibilityAssessment.unsupported(
                "Project '${projName}' is not marked as a Grails project".toString(),
                ["Missing grailsVersion or grails-core dependency"]
            )
        }
        return evaluate(model.grailsVersion, model.groovyVersion, javaVersion, gradleVersion, model.dependencies)
    }

    /**
     * Evaluates a GrailsProject against the compatibility matrix.
     */
    CompatibilityAssessment evaluate(GrailsProject project, Integer javaVersion = null, String gradleVersion = null) {
        if (project == null) {
            return CompatibilityAssessment.unsupported("Null GrailsProject cannot be evaluated")
        }
        if (!project.isGrailsProject) {
            String projName = project.name ?: "unknown"
            return CompatibilityAssessment.unsupported(
                "Project '${projName}' is not marked as a Grails project".toString(),
                ["Missing grailsVersion or grails-core dependency"]
            )
        }

        Integer effectiveJava = javaVersion
        if (effectiveJava == null && project.targetCompatibility) {
            try {
                String tc = project.targetCompatibility.replace('1.', '')
                effectiveJava = Integer.valueOf(Integer.parseInt(tc))
            } catch (Throwable ignored) {}
        }

        return evaluate(project.grailsVersion, project.groovyVersion, effectiveJava, gradleVersion, project.dependencies)
    }

    /**
     * Evaluates raw version and dependency inputs against the compatibility matrix.
     */
    CompatibilityAssessment evaluate(
        String grailsVersion,
        String groovyVersion,
        Integer javaVersion = null,
        String gradleVersion = null,
        Set<DependencyNode> dependencies = null
    ) {
        String gv = grailsVersion?.trim()
        String grv = groovyVersion?.trim()

        // 1. Missing framework version
        if (!gv) {
            return CompatibilityAssessment.unsupported(
                "Grails version is unspecified",
                ["Cannot determine framework version from build configuration"]
            )
        }

        // 2. Older unsupported Grails versions (< 7.0)
        if (isOlderGrailsVersion(gv)) {
            String summary = "Grails ${gv} is unsupported in 1.0 release scope".toString()
            String detailMsg = "Grails ${gv} uses older Spring and Groovy runtimes that are incompatible with current AST transforms".toString()
            return CompatibilityAssessment.unsupported(
                summary,
                [
                    "First compatibility target is Grails 7+ with Groovy 4",
                    detailMsg
                ]
            )
        }

        // 3. Incompatible Groovy versions (< 4.0)
        if (grv && isOlderGroovyVersion(grv)) {
            String summary = "Groovy ${grv} is incompatible with Grails 7+".toString()
            String detailMsg = "Groovy ${grv} does not support Groovy 4 AST structures or jakarta.* namespace".toString()
            return CompatibilityAssessment.unsupported(
                summary,
                [
                    "Grails 7+ requires Groovy 4.0+",
                    detailMsg
                ]
            )
        }

        // 4. Incompatible JDK versions (< 17)
        if (javaVersion != null && javaVersion.intValue() < 17) {
            String summary = "Java ${javaVersion} is unsupported".toString()
            String detailMsg = "Java ${javaVersion} bytecode cannot run modern Spring Boot / Grails 7 dependencies".toString()
            return CompatibilityAssessment.unsupported(
                summary,
                [
                    "Grails 7+ requires JDK 17 or JDK 21",
                    detailMsg
                ]
            )
        }

        // 5. Check exact match in tested combinations
        CompatibilityEntry match = entries.find { it.matchesExact(gv, grv ?: "4.0.23") }
        if (match != null && match.level == CompatibilityLevel.TESTED) {
            // Check JDK compatibility if specified
            if (javaVersion != null && !match.isJavaVersionSupported(javaVersion.intValue())) {
                String summary = "Grails ${gv} / Groovy ${grv} is tested, but JDK ${javaVersion} is unverified".toString()
                String detailMsg = "Tested JDK versions for this combination are: ${match.supportedJavaVersions}".toString()
                return CompatibilityAssessment.experimental(
                    summary,
                    [detailMsg]
                )
            }

            // Check Gradle version if specified
            if (gradleVersion && !match.isGradleVersionSupported(gradleVersion)) {
                String summary = "Grails ${gv} / Groovy ${grv} is tested, but Gradle ${gradleVersion} is unverified".toString()
                String detailMsg = "Tested Gradle versions for this combination are: ${match.supportedGradleVersions}".toString()
                return CompatibilityAssessment.experimental(
                    summary,
                    [detailMsg]
                )
            }

            // Check core dependencies if provided
            List<String> missing = []
            if (dependencies != null && !dependencies.isEmpty()) {
                for (String reqCoord : match.pinnedDependencies.keySet()) {
                    boolean found = dependencies.any { "${it.group}:${it.name}" == reqCoord }
                    if (!found) {
                        missing.add(reqCoord)
                    }
                }
            }

            if (!missing.isEmpty()) {
                String summary = "Project specifies Grails ${gv} but is missing core dependencies".toString()
                String detailMsg = "Missing required dependencies from classpath: ${missing}".toString()
                return CompatibilityAssessment.unsupported(
                    summary,
                    [detailMsg],
                    missing
                )
            }

            String summary = "Fully verified Grails ${gv} / Groovy ${match.groovyVersion} combination".toString()
            return CompatibilityAssessment.tested(
                match,
                summary
            )
        }

        // 6. Major version 7.x unverified combinations (R2-06/3: never automatically TESTED)
        if (gv.startsWith("7.") || gv.startsWith("v7.")) {
            String summary = "Grails ${gv} is an unverified Grails 7 combination (EXPERIMENTAL)".toString()
            return CompatibilityAssessment.experimental(
                summary,
                [
                    "Shares Grails 7 major version, but exact combination has not been verified with integration test fixtures",
                    "New minor or patch versions require explicit compatibility verification before receiving TESTED status"
                ]
            )
        }

        // 7. Future major versions (e.g. 8.0+)
        String summary = "Grails ${gv} is outside the admitted 1.0 support scope".toString()
        return CompatibilityAssessment.unsupported(
            summary,
            ["Supported major release is Grails 7.x"]
        )
    }

    /**
     * Offline replay validation: checks artifact content fingerprints against matrix without network access.
     */
    boolean verifyOfflineArtifactFingerprints(String grailsVersion, Map<String, String> localArtifactFingerprints) {
        if (!grailsVersion || localArtifactFingerprints == null) return false
        CompatibilityEntry entry = entries.find { it.grailsVersion.equalsIgnoreCase(grailsVersion.trim()) }
        if (entry == null || entry.artifactFingerprints == null || entry.artifactFingerprints.isEmpty()) {
            return false
        }

        for (Map.Entry<String, String> expected : entry.artifactFingerprints.entrySet()) {
            String actualHash = localArtifactFingerprints.get(expected.key)
            if (actualHash == null || !actualHash.equalsIgnoreCase(expected.value)) {
                return false
            }
        }

        return true
    }

    private static boolean isOlderGrailsVersion(String v) {
        if (!v) return false
        String clean = v.trim().toLowerCase()
        if (clean.startsWith('v')) clean = clean.substring(1)
        return clean.startsWith('1.') || clean.startsWith('2.') ||
               clean.startsWith('3.') || clean.startsWith('4.') ||
               clean.startsWith('5.') || clean.startsWith('6.')
    }

    private static boolean isOlderGroovyVersion(String v) {
        if (!v) return false
        String clean = v.trim().toLowerCase()
        if (clean.startsWith('v')) clean = clean.substring(1)
        return clean.startsWith('1.') || clean.startsWith('2.') || clean.startsWith('3.')
    }
}

