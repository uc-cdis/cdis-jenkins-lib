import hudson.tasks.test.AbstractTestResultAction

@NonCPS
def formatJunitForBuild(String firstLine, AbstractTestResultAction branchTestResultAction, AbstractTestResultAction masterTestResultAction) {
    def summary = firstLine

    def unpackBranch = getTestResultForBuild(branchTestResultAction)
    def total = unpackBranch[0]
    def failed = unpackBranch[1]
    def skipped = unpackBranch[2]
    def tests = unpackBranch[3]

    summary += junitReport(total, failed, skipped)
    summary += '\n'

    def unpackMaster = getTestResultForBuild(masterTestResultAction)
    def totalM = unpackMaster[0]
    def failedM = unpackMaster[1]
    def skippedM = unpackMaster[2]
    def testsM = unpackMaster[3]

    summary += junitReport(totalM, failedM, skippedM)
    summary += '\n'

    summary += '| Test | Time (`PR`) | Time (`master`) | Diff |'
    summary += '\n'
    summary += '| - | - | - | - |'
    summary += '\n'

    def both = [tests.sort{ it.getName() }, testsM.sort{ it.getName() }].transpose()

    for (def index = 0; index < both.size(); index++) {
        def testResult = both[index]

        def name = testResult[0].getName()
        def branchDuration = testResult[0].getDuration()
        def masterDuration = testResult[1].getDuration()
        def diffStr = String.format("%.2f", branchDuration - masterDuration)

        summary += "| ${name} | ${branchDuration} | ${masterDuration} | ${diffStr} |"
        summary += '\n'
    }

    return summary
}

@NonCPS
def getTestResultForBuild(AbstractTestResultAction testResultAction) {
    def results = testResultAction.getResult()

    def total = results.getTotalCount()
    def failed = results.getFailCount()
    def skipped = results.getSkipCount()
    def tests = []

    def passedTests = results.getPassedTests()

    for (def pti = 0; pti < passedTests.size(); pti++) {
        def passedTest = passedTests[pti]
        tests.add([passedTest.getTitle(), passedTest.getDuration()])
    }

    return [total, failed, skipped, passedTests]
}

@NonCPS
def junitReport(Integer total, Integer failed, Integer skipped) {
    def summary = "Test results:\n\t"
    summary += ("Passed: " + (total - failed - skipped))
    summary += (", Failed: " + failed)
    summary += (", Skipped: " + skipped)
    return summary
}

@NonCPS
def junitReportTable() {
    def currentTestResult = currentBuild.rawBuild.getAction(AbstractTestResultAction.class)
    def masterTestResult = Jenkins.instance.getAllItems(Job.class).findAll{
        it.name == 'master'
    }.collect{ it.getLastSuccessfulBuild().getAction(AbstractTestResultAction.class) }.first()

    firstLine = "Jenkins Build ${env.BUILD_NUMBER} : time taken ${currentBuild.durationString.replace(' and counting', '')}\nCheck the ${RUN_DISPLAY_URL}\n\n\n"
    r = formatJunitForBuild(firstLine, currentTestResult, masterTestResult)
    return r
}
