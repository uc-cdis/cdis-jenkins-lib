def checkTestSkippingCriteria() {
  HashMap fileChanges = gitHelper.getLatestChangeOfBranch('HEAD')
  for (String key : fileChanges.keySet()) {
    fileChange = fileChanges[key][0]
    def releasesFolder = fileChange =~ /^(releases\/.*)/
    def textFile = fileChange =~ /(.*\.md)|(.*\.txt)|(.*\.feature)/
    if (releasesFolder) {
      println('Found releases folder: ' + releasesFolder[0][0])
    } else if (textFile) {
      println('Found text file: ' + textFile[0][0])
    } else {
      println('This PR is eligible for testing')
      return false
    }
  }
  println('doc-only changes, skipping tests...')
  return true
}
