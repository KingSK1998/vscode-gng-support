package kingsk.grails.lsp.core.analysis

import groovy.transform.CompileStatic

@CompileStatic
class ChangeChecksSummary {
    String checkStatus = "unknown"
    int testsTotal = 0
    int testsPassed = 0
    int testsFailed = 0
    int testsSkipped = 0
    boolean compilationSuccess = true
    int errorCount = 0
    int warningCount = 0

    Map<String, Object> toMap() {
        return [
            checkStatus: checkStatus,
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
