import org.apache.commons.lang.StringUtils;

def assembleFeatureLabelMap(failedTestSuites) {
  def featureLabelMap = [:]
  try {
    // correlate failed test suites with the script file path from result xmls
    def xmlResultFilesRaw = sh(returnStdout: true, script: "ls output/result*.xml")
    def xmlResultFiles = xmlResultFilesRaw.split('\n')
    println(xmlResultFiles)

    xmlResultFiles.each{ xmlResultFile->
      failedTestSuites.each{ failedTestSuite->
        println('obtaining file path from test suite...')
        def filePathFromFailedTestSuiteRaw = sh(
          returnStdout: true,
          script: "python -c \"import lxml.etree; print(''.join(lxml.etree.parse(\\\"${xmlResultFile}\\\").xpath('//testsuites/testsuite[@name=\\\"${failedTestSuite}\\\"]/@file')))\""
        )

        // split file path from failed test suite
        def j = filePathFromFailedTestSuiteRaw.split("/")

        def testSelectorlabel = "test-" + j[j.length-2] + "-" + j[j.length-1].substring(0, j[j.length-1].indexOf("."))

        println("test selection label: " + testSelectorlabel)
	featureLabelMap[failedTestSuite] = testSelectorlabel
      }
    }

    return featureLabelMap;
  } catch (e) {
    pipelineHelper.handleError(e)
  }
  return null;
}

def identifyFailedTestSuites() {
  List<String> failedTestSuites = [];
  def xmlTestSuiteFilesRaw = sh(returnStdout: true, script: "ls output/*-testsuite.xml")
  def xmlTestSuiteFiles = xmlTestSuiteFilesRaw.split('\n')
  println(xmlTestSuiteFiles)

  xmlTestSuiteFiles.each{ xmlFile->
    println('reading test suite file... ' + xmlFile)
                  
    def testSuiteName = sh(
      returnStdout: true,
      script: "python -c \"import lxml.etree; print(lxml.etree.parse(\\\"${xmlFile}\\\").xpath('//ns2:test-suite/name/text()', namespaces={'ns2': 'urn:model.allure.qatools.yandex.ru'})[0].split(':')[0])\""
    )

    println(testSuiteName)

    def testSuiteResultsRaw = sh(
      returnStdout: true,
      script: "python -c \"import lxml.etree; print(','.join(lxml.etree.parse(\\\"${xmlFile}\\\").xpath('//ns2:test-suite/test-cases/test-case/@status', namespaces={'ns2': 'urn:model.allure.qatools.yandex.ru'})))\""
    )

    println('### ##' + StringUtils.chomp(testSuiteResultsRaw).split(","))

    def testSuiteResults = StringUtils.chomp(testSuiteResultsRaw).split(",")
    println(testSuiteResults)
                  
    if ('failed' in testSuiteResults) {
      failedTestSuites.add( StringUtils.chomp(testSuiteName) )
    }
  }
  println("full list of failedTestSuites: ${failedTestSuites}")
  return failedTestSuites
}
