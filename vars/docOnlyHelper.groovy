def checkTestSkippingCriteria() {
  HashMap fileChanges = gitHelper.getLatestChangeOfBranch('HEAD')
  for (String key : fileChanges.keySet()) {
    fileChange = fileChanges[key][0]
    def releasesFolder = fileChange =~ /^(releases\/.*)/
    def markdownFile = fileChange =~ /(.*\.md)/
    if (releasesFolder) {
      println('Found releases folder: ' + releasesFolder[0][0])
    } else if (markdownFile) {
      println('Found markdown file: ' + markdownFile[0][0])
    } else {
      println('This PR is eligible for testing')
      return false
    }
  }
  println('doc-only changes, skipping tests...')
  return true
}
