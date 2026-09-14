package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class ProjectCapabilitiesResponse implements Serializable {
    final int schemaVersion = 1
    String projectUri
    String projectName
    String projectType
    String analysisRevision
    String grailsVersion
    String groovyVersion
    String javaVersion
    String compatibilityLevel
    String freshness
    String completeness
    List<CapabilityDescriptor> capabilities = []
    List<String> limitations = []
    List<String> reasons = []

    Map<String, Object> toMap() {
        return [
            schemaVersion: schemaVersion,
            projectUri: projectUri,
            projectName: projectName,
            projectType: projectType,
            analysisRevision: analysisRevision,
            grailsVersion: grailsVersion,
            groovyVersion: groovyVersion,
            javaVersion: javaVersion,
            compatibilityLevel: compatibilityLevel,
            freshness: freshness,
            completeness: completeness,
            capabilities: capabilities.collect { it.toMap() },
            limitations: limitations,
            reasons: reasons
        ]
    }
}

