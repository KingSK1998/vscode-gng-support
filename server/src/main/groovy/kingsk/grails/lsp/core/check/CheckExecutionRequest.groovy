package kingsk.grails.lsp.core.check

import groovy.transform.CompileStatic

@CompileStatic
class CheckExecutionRequest implements Serializable {
    String projectUri
    String checkType
    CheckScope scope
    Long timeoutMs
    Long maxOutputBytes
    Map<String, String> environment = [:]

    CheckExecutionRequest() {}
}
