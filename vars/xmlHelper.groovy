//import org.apache.commons.lang.StringUtils;
import groovy.xml.XmlUtil;

// correlate failed test suites with the script file path from result xmls
def assembleFeatureLabelMap() {
  def featureLabelMap = [:]
  try {
    // avoid "No such file or directory" error if runTests fails before CodeceptJS test reports are generated
    def xmlResultFilesRaw = sh(returnStdout: true, script: "ls output/result*.xml || echo \"Warn: there are no output/result-*.xml files to parse\" ")
    if (xmlResultFilesRaw.contains('Warn')) {
      return null;
    }
    
    def xmlResultFiles = xmlResultFilesRaw.split('\n')
    println(xmlResultFiles)
    
    xmlResultFiles.each{ xmlResultFile->
      def xmlResultString = sh(returnStdout: true, script: "cat ${xmlResultFile}").toString().trim()
      def xmlResults = new XmlSlurper().parseText(xmlResultString)

      xmlResults.testsuite.each { testsuite ->
        println("### checking testsuite: ${testsuite.@name}")
        if (testsuite.@failures.toInteger() > 0){
          def failedTestSuite = testsuite.@name
          def filePath = testsuite.@file.toString()
          def j = filePath.split("/")
          def testSelectorlabel = "test-" + j[j.length-2] + "-" + j[j.length-1].substring(0, j[j.length-1].indexOf("."))
        
          featureLabelMap[failedTestSuite] = testSelectorlabel
          println "### ##Found failed test suite: ${failedTestSuite} with label ${testSelectorlabel}"
        }
    }
    
    println("### ## Here are the failed suites: ${featureLabelMap}")
    return featureLabelMap
  }
  catch (e) {
  println("Something wrong happened: ${e}")
  println("Ignore and return null map")
  return null;
  }
  return null;
}
