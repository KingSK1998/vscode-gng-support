package kingsk.grails.lsp.core.check

import groovy.transform.CompileStatic

@CompileStatic
class CheckSummary implements Serializable {
    int testsTotal = 0
    int testsPassed = 0
    int testsFailed = 0
    int testsSkipped = 0
    boolean compilationSuccess = true
    int errorCount = 0
    int warningCount = 0

    CheckSummary() {}

    Map<String, Object> toMap() {
        return [
            testsTotal: testsTotal,
            testsPassed: testsPassed,
            testsFailed: testsFailed,
            testsSkipped: testsSkipped,
            compilationSuccess: compilationSuccess,
            errorCount: errorCount,
            warningCount: warningCount
        ]
    }
}
