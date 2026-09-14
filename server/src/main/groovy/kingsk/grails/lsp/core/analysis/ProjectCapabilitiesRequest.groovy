package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class ProjectCapabilitiesRequest implements Serializable {
    String projectUri
    String sourceSet

    ProjectCapabilitiesRequest() {}

    ProjectCapabilitiesRequest(String projectUri, String sourceSet = null) {
        this.projectUri = projectUri
        this.sourceSet = sourceSet
    }
}

