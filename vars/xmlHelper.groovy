import org.apache.commons.lang.StringUtils;

// correlate failed test suites with the script file path from result xmls
def assembleFeatureLabelMap(failedTestSuites) {
  def featureLabelMap = [:]
  try {
    // avoid "No such file or directory" error if runTests fails before CodeceptJS test reports are generated
    def xmlResultFilesRaw = sh(returnStdout: true, script: "[ \"\$(ls -A output)\" ] && ls output/result*.xml || echo \"Warn: there are no output/result-*.xml files to parse\" ")
    if (xmlResultFilesRaw.contains('Warn')) {
      return null;
    }
    def xmlResultFiles = xmlResultFilesRaw.split('\n')
    println(xmlResultFiles)

    xmlResultFiles.each{ xmlResultFile->
      failedTestSuites.each{ failedTestSuite->
        println('obtaining file path from test suite...')
        def filePathFromFailedTestSuiteRaw = sh(
          returnStdout: true,
          script: "python -c \"import lxml.etree; print(''.join(lxml.etree.parse(\\\"${xmlResultFile}\\\").xpath('//testsuites/testsuite[@name=\\\"${failedTestSuite}\\\"]/@file')))\""
        )

        if (filePathFromFailedTestSuiteRaw.trim().length() > 0) {
          // split file path from failed test suite
          def j = filePathFromFailedTestSuiteRaw.split("/")

          def testSelectorlabel = "test-" + j[j.length-2] + "-" + j[j.length-1].substring(0, j[j.length-1].indexOf("."))

          println("test selection label: " + testSelectorlabel)
          featureLabelMap[failedTestSuite] = testSelectorlabel
        } else {
          println("The test suite named [${failedTestSuite}] was not found in [${xmlResultFile}], checking the next one..")   
        }
      }
    }

    return featureLabelMap;
  } catch (e) {
    println("Something wrong happened: ${e}")
    println("Ignore and return null map")
    return null;
  }
  return null;
}

def identifyFailedTestSuites() {
  List<String> failedTestSuites = [];
  try {
    // avoid "No such file or directory" error if runTests fails before CodeceptJS test reports are generated
    def xmlTestSuiteFilesRaw = sh(returnStdout: true, script: "[ \"\$(ls -A output)\" ] && ls output/*-testsuite.xml || echo \"Warn: there are no output/*-testsuite.xml files to parse\" ")
    if (xmlTestSuiteFilesRaw.contains('Warn')) {
      return [];
    }
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

      // if the test contains a result that is not "passed"
      if ('failed' in testSuiteResults) {
        // if the test has succeeded on retries
        // it will contain something like [failed,failed,passed]
        if (testSuiteResults.last() == "passed") {
          println("WARN: Test suite succeeded on retries!")
          return
        }
        // if a single test scenario of the suite failed
        // add the suite name to the list of failures
        failedTestSuites.add( StringUtils.chomp(testSuiteName) )
      }
    }
    println("full list of failedTestSuites: ${failedTestSuites}")
  } catch (e) {
    println("Something wrong happened: ${e}")
    println("Ignore and return empty list")
    return [];
  }
  return failedTestSuites
}
