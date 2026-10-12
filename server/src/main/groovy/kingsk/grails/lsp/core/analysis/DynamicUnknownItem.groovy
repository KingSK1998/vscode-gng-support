package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class DynamicUnknownItem implements Serializable {
    final String sourceUri
    final String description
    final String risk
    final String mitigation

    DynamicUnknownItem(String sourceUri, String description, String risk, String mitigation) {
        this.sourceUri = sourceUri
        this.description = description
        this.risk = risk
        this.mitigation = mitigation
    }

    Map<String, Object> toMap() {
        return [
            sourceUri: sourceUri,
            description: description,
            risk: risk,
            mitigation: mitigation
        ]
    }
}
