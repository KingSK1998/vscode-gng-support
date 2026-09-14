package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class CapabilityDescriptor implements Serializable {
    final String name
    final boolean enabled
    final String detectionEvidence
    final String applicability
    final String description

    CapabilityDescriptor(
        String name,
        boolean enabled,
        String detectionEvidence,
        String applicability,
        String description
    ) {
        this.name = name
        this.enabled = enabled
        this.detectionEvidence = detectionEvidence
        this.applicability = applicability
        this.description = description
    }

    String getDerivation() {
        return description
    }

    Map<String, Object> toMap() {
        return [
            name: name,
            enabled: enabled,
            detectionEvidence: detectionEvidence,
            applicability: applicability,
            description: description
        ]
    }
}

