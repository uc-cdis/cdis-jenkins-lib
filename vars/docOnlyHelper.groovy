def checkTestSkippingCriteria() {
  HashMap fileChanges = gitHelper.getLatestChangeOfBranch('HEAD')
  for (String key : fileChanges.keySet()) { // for each environment
    for (String fileChange : fileChanges[key]) { // for each changed file
      def releasesFolder = fileChange =~ /^(releases\/.*)/
      def dotFolder = fileChange =~ /^(\..*)/
      def docFile = fileChange =~ /(.*\.md)|(.*\.png)|(.*\.txt)|(.*\.feature)|(.*\.xlsx)|(.*\.pdf)|(CODEOWNERS)|(.*\/swagger.yaml)|(.*\/openapi.yaml)/
      if (releasesFolder) {
        println('Found releases folder: ' + releasesFolder[0][0])
      } else if (dotFolder) {
        println('Found changes in a file inside dot folder: ' + dotFolder[0][0])
      } else if (docFile) {
        println('Found text file: ' + docFile[0][0])
      } else {
        println('Found file ' + fileChange + ': this PR is eligible for testing')
        return false
      }
    }
  }
  println('doc-only changes, skipping tests...')
  return true
}
