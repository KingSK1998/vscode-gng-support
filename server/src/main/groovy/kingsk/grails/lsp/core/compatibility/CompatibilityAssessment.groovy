package kingsk.grails.lsp.core.compatibility

import groovy.transform.CompileStatic
import groovy.transform.Immutable

import java.util.Collections
import java.util.List

/**
 * Result of evaluating a project, model, or runtime combination against the compatibility matrix.
 * Conforms to R2-06.
 */
@Immutable
@CompileStatic
class CompatibilityAssessment {
    CompatibilityLevel level
    CompatibilityEntry matchedEntry
    String summary
    List<String> details
    List<String> missingDependencies

    boolean isSupported() {
        return level == CompatibilityLevel.TESTED || level == CompatibilityLevel.EXPERIMENTAL
    }

    boolean isTested() {
        return level == CompatibilityLevel.TESTED
    }

    boolean isExperimental() {
        return level == CompatibilityLevel.EXPERIMENTAL
    }

    boolean isUnsupported() {
        return level == CompatibilityLevel.UNSUPPORTED
    }

    static CompatibilityAssessment tested(CompatibilityEntry entry, String summary = "Exact verified combination") {
        String detailMsg = "Matched tested Grails ${entry.grailsVersion} / Groovy ${entry.groovyVersion} entry".toString()
        return new CompatibilityAssessment(
            CompatibilityLevel.TESTED,
            entry,
            summary,
            Collections.unmodifiableList([detailMsg]),
            Collections.emptyList()
        )
    }

    static CompatibilityAssessment experimental(String summary, List<String> details = []) {
        return new CompatibilityAssessment(
            CompatibilityLevel.EXPERIMENTAL,
            null,
            summary,
            Collections.unmodifiableList(details),
            Collections.emptyList()
        )
    }

    static CompatibilityAssessment unsupported(String summary, List<String> details = [], List<String> missing = []) {
        return new CompatibilityAssessment(
            CompatibilityLevel.UNSUPPORTED,
            null,
            summary,
            Collections.unmodifiableList(details),
            Collections.unmodifiableList(missing)
        )
    }
}

