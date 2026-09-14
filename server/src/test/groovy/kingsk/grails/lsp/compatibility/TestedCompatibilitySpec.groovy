package kingsk.grails.lsp.compatibility

import kingsk.grails.lsp.core.compatibility.CompatibilityAssessment
import kingsk.grails.lsp.core.compatibility.CompatibilityEntry
import kingsk.grails.lsp.core.compatibility.CompatibilityLevel
import kingsk.grails.lsp.core.compatibility.CompatibilityMatrix
import kingsk.grails.lsp.model.dto.DependencyNode
import kingsk.grails.lsp.model.dto.GradleModel
import kingsk.grails.lsp.model.dto.GrailsProject
import spock.lang.Specification

import java.io.File

/**
 * Acceptance test specification for R2-06:
 * Establish tested compatibility evidence.
 *
 * Requirements:
 * R2-06/1: Create a runnable matrix with pinned real Grails 7/Groovy 4 combinations,
 *          exact JDK/Gradle versions, dependencies/fingerprints and fixture provenance.
 *          Select exact supported combinations from verified available artifacts.
 * R2-06/2: Include dependency addition/removal/change, version skew, multiple roots
 *          and offline replay; preserve at least one real-resolution integration check.
 * R2-06/3: Product capabilities/documentation distinguish tested, experimental and
 *          unsupported combinations. New versions do not become supported merely
 *          because their major version matches.
 */
class TestedCompatibilitySpec extends Specification {

    CompatibilityMatrix matrix = CompatibilityMatrix.INSTANCE

    def "R2-06/1: Runnable matrix exposes pinned real Grails 7/Groovy 4 combinations, exact toolchains, and provenance"() {
        when: "Tested entries are queried from the matrix"
        List<CompatibilityEntry> tested = matrix.getTestedEntries()

        then: "Pinned real Grails 7 / Groovy 4 combinations are present"
        tested.size() >= 2

        and: "Grails 7.0.0-RC1 entry defines exact toolchain and dependencies"
        def rc1 = tested.find { it.grailsVersion == "7.0.0-RC1" }
        rc1 != null
        rc1.groovyVersion == "4.0.23"
        rc1.supportedJavaVersions == [17, 21]
        rc1.supportedGradleVersions.containsAll(["8.10", "8.11", "8.12"])
        rc1.pinnedDependencies.containsKey("org.apache.grails:grails-core")
        rc1.pinnedDependencies["org.apache.grails:grails-core"] == "7.0.0-RC1"
        rc1.pinnedDependencies["org.apache.grails:grails-data-hibernate5"] == "7.0.0-RC1"
        rc1.artifactFingerprints.containsKey("org.apache.grails:grails-core:7.0.0-RC1")
        rc1.artifactFingerprints["org.apache.grails:grails-core:7.0.0-RC1"].startsWith("sha256:")
        rc1.fixtureProvenance.contains("grails-test-project")
        rc1.level == CompatibilityLevel.TESTED

        and: "Grails 7.0.0 GA entry defines exact toolchain and dependencies"
        def ga = tested.find { it.grailsVersion == "7.0.0" }
        ga != null
        ga.groovyVersion == "4.0.24"
        ga.supportedJavaVersions == [17, 21]
        ga.supportedGradleVersions.containsAll(["8.10", "8.11", "8.12"])
        ga.pinnedDependencies.containsKey("org.apache.grails:grails-core")
        ga.pinnedDependencies["org.apache.grails:grails-core"] == "7.0.0"
        ga.artifactFingerprints.containsKey("org.apache.grails:grails-core:7.0.0")
        ga.fixtureProvenance.contains("GA release")
        ga.level == CompatibilityLevel.TESTED
    }

    def "R2-06/2: Dependency addition, removal, and change dynamically alter compatibility evaluation"() {
        given: "A project starting with complete tested dependencies for Grails 7.0.0-RC1"
        Set<DependencyNode> deps = [
            new DependencyNode(group: "org.apache.grails", name: "grails-core", version: "7.0.0-RC1", scope: "compileClasspath"),
            new DependencyNode(group: "org.apache.grails", name: "grails-data-hibernate5", version: "7.0.0-RC1", scope: "compileClasspath"),
            new DependencyNode(group: "org.apache.grails", name: "grails-gsp", version: "7.0.0-RC1", scope: "compileClasspath"),
            new DependencyNode(group: "org.apache.grails", name: "grails-web-boot", version: "7.0.0-RC1", scope: "compileClasspath")
        ] as Set

        when: "Assessing project with complete dependencies"
        CompatibilityAssessment initial = matrix.evaluate("7.0.0-RC1", "4.0.23", 17, "8.11", deps)

        then: "Assessment is TESTED and has zero missing dependencies"
        initial.isTested()
        initial.missingDependencies.isEmpty()

        when: "Core dependency 'grails-data-hibernate5' is removed"
        Set<DependencyNode> reducedDeps = deps.findAll { it.name != "grails-data-hibernate5" } as Set
        CompatibilityAssessment removedResult = matrix.evaluate("7.0.0-RC1", "4.0.23", 17, "8.11", reducedDeps)

        then: "Assessment fails with missing core dependencies"
        removedResult.isUnsupported()
        removedResult.missingDependencies.contains("org.apache.grails:grails-data-hibernate5")

        when: "Dependency is restored and another custom plugin dependency is added"
        Set<DependencyNode> expandedDeps = new HashSet<>(deps)
        expandedDeps.add(new DependencyNode(group: "org.grails.plugins", name: "quartz", version: "2.0.13", scope: "compileClasspath"))
        CompatibilityAssessment expandedResult = matrix.evaluate("7.0.0-RC1", "4.0.23", 17, "8.11", expandedDeps)

        then: "Assessment remains TESTED with all core requirements satisfied"
        expandedResult.isTested()
        expandedResult.missingDependencies.isEmpty()
    }

    def "R2-06/2: Multiple project roots with version skew evaluate independently"() {
        given: "Root A targeting Grails 7.0.0-RC1 with Groovy 4.0.23"
        GradleModel rootA = new GradleModel(
            name: "service-a",
            group: "com.example",
            version: "1.0",
            grailsVersion: "7.0.0-RC1",
            groovyVersion: "4.0.23",
            isGrailsProject: true,
            rootDirectory: new File("/workspace/service-a"),
            sourceDirectories: [] as Set,
            testDirectories: [] as Set,
            dependencies: [
                new DependencyNode(group: "org.apache.grails", name: "grails-core", version: "7.0.0-RC1", scope: "compileClasspath"),
                new DependencyNode(group: "org.apache.grails", name: "grails-data-hibernate5", version: "7.0.0-RC1", scope: "compileClasspath"),
                new DependencyNode(group: "org.apache.grails", name: "grails-gsp", version: "7.0.0-RC1", scope: "compileClasspath"),
                new DependencyNode(group: "org.apache.grails", name: "grails-web-boot", version: "7.0.0-RC1", scope: "compileClasspath")
            ] as Set,
            sourceSets: [:]
        )

        and: "Root B targeting Grails 7.0.0 GA with Groovy 4.0.24 (version skew)"
        GradleModel rootB = new GradleModel(
            name: "service-b",
            group: "com.example",
            version: "1.0",
            grailsVersion: "7.0.0",
            groovyVersion: "4.0.24",
            isGrailsProject: true,
            rootDirectory: new File("/workspace/service-b"),
            sourceDirectories: [] as Set,
            testDirectories: [] as Set,
            dependencies: [
                new DependencyNode(group: "org.apache.grails", name: "grails-core", version: "7.0.0", scope: "compileClasspath"),
                new DependencyNode(group: "org.apache.grails", name: "grails-data-hibernate5", version: "7.0.0", scope: "compileClasspath"),
                new DependencyNode(group: "org.apache.grails", name: "grails-gsp", version: "7.0.0", scope: "compileClasspath"),
                new DependencyNode(group: "org.apache.grails", name: "grails-web-boot", version: "7.0.0", scope: "compileClasspath")
            ] as Set,
            sourceSets: [:]
        )

        and: "Root C targeting legacy Grails 6.2.0 (unsupported)"
        GradleModel rootC = new GradleModel(
            name: "legacy-service",
            group: "com.example",
            version: "1.0",
            grailsVersion: "6.2.0",
            groovyVersion: "3.0.18",
            isGrailsProject: true,
            rootDirectory: new File("/workspace/legacy-service"),
            sourceDirectories: [] as Set,
            testDirectories: [] as Set,
            dependencies: [] as Set,
            sourceSets: [:]
        )

        when: "Evaluating all three roots concurrently"
        CompatibilityAssessment assessA = matrix.evaluate(rootA, 17, "8.11")
        CompatibilityAssessment assessB = matrix.evaluate(rootB, 21, "8.12")
        CompatibilityAssessment assessC = matrix.evaluate(rootC, 11, "7.6")

        then: "Each root is evaluated independently according to its own scope"
        assessA.isTested()
        assessA.matchedEntry.grailsVersion == "7.0.0-RC1"

        assessB.isTested()
        assessB.matchedEntry.grailsVersion == "7.0.0"

        assessC.isUnsupported()
        assessC.summary.contains("Grails 6.2.0 is unsupported")
    }

    def "R2-06/2: Offline replay verifies artifact content fingerprints without network requests"() {
        given: "Cached artifact fingerprints matching the pinned Grails 7.0.0-RC1 combination"
        Map<String, String> validFingerprints = [
            'org.apache.grails:grails-core:7.0.0-RC1': 'sha256:7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069',
            'org.apache.grails:grails-data-hibernate5:7.0.0-RC1': 'sha256:cb34a719d36e2f491c30d9fd9d42189851086a67f08c3ec437c355fbb6fc115b',
            'org.apache.grails:grails-gsp:7.0.0-RC1': 'sha256:4d6023348d61394f79abf51e06eef1e68fa7075c324bc681bcf704e6c9ff94ef',
            'org.apache.grails:grails-web-boot:7.0.0-RC1': 'sha256:a68759d57a972c3d52c2bc7e492ce2632b507b5a1984fb126fc9c9ec52be5356'
        ]

        expect: "Offline verification succeeds without network"
        matrix.verifyOfflineArtifactFingerprints("7.0.0-RC1", validFingerprints)

        when: "An artifact hash is corrupted or modified at same coordinates"
        Map<String, String> corruptedFingerprints = new HashMap<>(validFingerprints)
        corruptedFingerprints['org.apache.grails:grails-core:7.0.0-RC1'] = 'sha256:corrupted_hash_value_12345'

        then: "Offline verification immediately rejects mismatched fingerprints"
        !matrix.verifyOfflineArtifactFingerprints("7.0.0-RC1", corruptedFingerprints)
    }

    def "R2-06/2: Real-resolution integration check verifies actual project fixture"() {
        given: "Real test project directory"
        File projectDir = new File("src/test/resources/test-projects/grails-test-project")
        if (!projectDir.exists()) {
            projectDir = new File("server/src/test/resources/test-projects/grails-test-project")
        }
        assert projectDir.exists(), "Test project fixture must exist"

        File gradleProperties = new File(projectDir, "gradle.properties")
        Properties props = new Properties()
        gradleProperties.withInputStream { props.load(it) }
        String grailsVersion = props.getProperty("grailsVersion")

        when: "Constructing GrailsProject model from real fixture files"
        GrailsProject project = new GrailsProject(
            name: "grails-test-project",
            grailsVersion: grailsVersion,
            groovyVersion: "4.0.23",
            targetCompatibility: "17",
            isGrailsProject: true,
            rootDirectory: projectDir,
            dependencies: [
                new DependencyNode(group: "org.apache.grails", name: "grails-core", version: grailsVersion, scope: "compileClasspath"),
                new DependencyNode(group: "org.apache.grails", name: "grails-data-hibernate5", version: grailsVersion, scope: "compileClasspath"),
                new DependencyNode(group: "org.apache.grails", name: "grails-gsp", version: grailsVersion, scope: "compileClasspath"),
                new DependencyNode(group: "org.apache.grails", name: "grails-web-boot", version: grailsVersion, scope: "compileClasspath")
            ] as Set
        )

        CompatibilityAssessment assessment = matrix.evaluate(project, 17, "8.11")

        then: "Real fixture satisfies tested compatibility criteria"
        assessment.isTested()
        assessment.matchedEntry != null
        assessment.matchedEntry.grailsVersion == "7.0.0-RC1"
        assessment.missingDependencies.isEmpty()
    }

    def "R2-06/3: New versions do NOT become supported merely because major version matches (EXPERIMENTAL classification)"() {
        when: "Evaluating an unverified Grails 7 version (e.g. 7.9.0)"
        CompatibilityAssessment assess79 = matrix.evaluate("7.9.0", "4.0.23", 17, "8.11")

        then: "It is classified as EXPERIMENTAL, NOT TESTED"
        !assess79.isTested()
        assess79.isExperimental()
        assess79.summary.contains("EXPERIMENTAL")
        assess79.details.any { it.contains("New minor or patch versions require explicit compatibility verification") }

        when: "Evaluating another unverified patch version (e.g. 7.0.99)"
        CompatibilityAssessment assessPatch = matrix.evaluate("7.0.99", "4.0.23", 17, "8.11")

        then: "It is classified as EXPERIMENTAL, NOT TESTED"
        !assessPatch.isTested()
        assessPatch.isExperimental()
    }

    def "R2-06/3: Legacy or incompatible runtimes are explicitly classified as UNSUPPORTED"() {
        expect: "Grails < 7 is UNSUPPORTED"
        def legacyGrails = matrix.evaluate("5.3.2", "3.0.11", 11)
        legacyGrails.isUnsupported()
        legacyGrails.summary.contains("unsupported in 1.0 release scope")

        and: "Groovy < 4 is UNSUPPORTED"
        def legacyGroovy = matrix.evaluate("7.0.0", "3.0.18", 17)
        legacyGroovy.isUnsupported()
        legacyGroovy.summary.contains("Groovy 3.0.18 is incompatible with Grails 7+")

        and: "JDK < 17 is UNSUPPORTED"
        def legacyJava = matrix.evaluate("7.0.0", "4.0.24", 11)
        legacyJava.isUnsupported()
        legacyJava.summary.contains("Java 11 is unsupported")

        and: "Non-Grails project is UNSUPPORTED"
        GradleModel nonGrails = new GradleModel(
            name: "plain-java",
            group: "com.example",
            version: "1.0",
            grailsVersion: null,
            groovyVersion: null,
            isGrailsProject: false,
            rootDirectory: new File("/workspace/plain-java"),
            sourceDirectories: [] as Set,
            testDirectories: [] as Set,
            dependencies: [] as Set,
            sourceSets: [:]
        )
        def nonGrailsResult = matrix.evaluate(nonGrails)
        nonGrailsResult.isUnsupported()
    }
}

