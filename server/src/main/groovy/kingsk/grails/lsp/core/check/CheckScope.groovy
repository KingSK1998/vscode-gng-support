package kingsk.grails.lsp.core.check

import groovy.transform.CompileStatic

@CompileStatic
class CheckScope implements Serializable {
    String sourceSet
    List<String> classes = []
    List<String> methods = []
    List<String> tasks = []

    CheckScope() {}

    Map<String, Object> toMap() {
        return [
            sourceSet: sourceSet,
            classes: classes,
            methods: methods,
            tasks: tasks
        ]
    }
}
