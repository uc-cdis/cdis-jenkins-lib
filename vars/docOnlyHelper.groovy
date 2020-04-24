def checkTestSkippingCriteria() {
  HashMap fileChanges = gitHelper.getLatestChangeOfBranch('HEAD')
  for (String key : fileChanges.keySet()) {
    fileChange = fileChanges[key][0]
    def releasesFolder = fileChange =~ /^(releases\/.*)/
    def openapisFolder = fileChange =~ /^(openapis\/*.yaml)/
    def docFile = fileChange =~ /(.*\.md)|(.*\.png)|(.*\.txt)|(.*\.feature)/
    if (releasesFolder) {
      println('Found releases folder: ' + releasesFolder[0][0])
    } else if (openapisFolder) {
      println('Found yaml file: ' + openapisFolder[0][0])
    } else if (docFile) {
      println('Found text file: ' + docFile[0][0])
    } else {
      println('This PR is eligible for testing')
      return false
    }
  }
  println('doc-only changes, skipping tests...')
  return true
}
