import groovy.util.XmlSlurper
import groovy.xml.XmlUtil

def assembleFeatureLabelMap(String theXML) {
  def featureLabelMap = [:]
  try {
    def doc = new XmlSlurper().parseText(theXML)

    doc.children().each{ test_case->
      if (test_case.@'name' != 'Root Suite') {
        // println(test_case.@'name'.text().split(' ')[0]) 
        // println(test_case.@'file'.text()) 
    
        f = test_case.@'file'.text().split("/")
        testScriptInLabelFormat = "test-" + f[f.size()-2] + "-" + f[f.size()-1]
    
        // println("testScriptInLabelFormat: ${testScriptInLabelFormat}")
    
        featureLabelMap[test_case.@'name'.text().split(' ')[0]] = testScriptInLabelFormat
      }
    };
    return featureLabelMap;
  } catch (e) {
    pipelineHelper.handleError(e)
  }
  return null;
}

def filterTags(String theXML, String tagToIterate, String paramToVerify, String condition, String tagToRetrieve) {
  List<String> filteredValues = []
  try{
    def doc = new XmlSlurper().parseText(theXML)
    def suiteName = doc.name.text().replaceAll(':', '')
    doc[tagToIterate].children().each{ tag->
      println("verifying the ${paramToVerify} of one of the test cases from suite [${suiteName}] (${tag.name})...")
      println("${paramToVerify}: " + tag.@"${paramToVerify}")
      theValue = tag.@"${paramToVerify}"
      if (theValue == condition) {
        if (tag[tagToRetrieve].size() > 0) {
          filteredValues.add(suiteName)
        }
      }
    }
    return filteredValues.unique()
  } catch (e) {
    pipelineHelper.handleError(e)
  }
  return null;
}