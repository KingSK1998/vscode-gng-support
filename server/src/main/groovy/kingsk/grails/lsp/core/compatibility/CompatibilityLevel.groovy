package kingsk.grails.lsp.core.compatibility

import groovy.transform.CompileStatic

/**
 * Support level classification for Grails / Groovy / JDK / Gradle runtime combinations.
 * Conforms to R2-06 and docs/specs/library-discovery.md.
 */
@CompileStatic
enum CompatibilityLevel {
    /**
     * Pinned combinations verified by real fixture artifacts, exact version pins, and automated tests.
     */
    TESTED,

    /**
     * Major framework version matches (e.g. Grails 7.x) and compatible API surface is detected,
     * but the specific combination lacks pinned integration test verification.
     */
    EXPERIMENTAL,

    /**
     * Incompatible framework (< 7.0), language runtime (< Groovy 4), or JDK (< 17).
     */
    UNSUPPORTED
}

