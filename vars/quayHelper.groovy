/**
* Waits for Quay to finish building the branch in config
*/
import org.apache.commons.lang.StringUtils;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

def waitForBuild(String repoName, String formattedBranch
) {
  if (repoName == "jenkins-lib" || repoName.contains("dictionary")) { return "skip" }
  echo("Waiting for Quay to build:\n  repoName: ${repoName}\n  branch: '${formattedBranch}'\n  commit: ${env.GIT_COMMIT}\n  previous commit: ${env.GIT_PREVIOUS_COMMIT}")
  String commitTimestamp = gitHelper.getTimestampOfLatestCommit('HEAD')
  def commitTime = new Date(Long.valueOf(commitTimestamp) * 1000 )
  QUAY_API = 'https://quay.io/api/v1/repository/cdis/'
  url = "$QUAY_API"+repoName+"/tag/"
  query = "curl -s "+url+/ |  jq '[.tags[]|select(.name=="${formattedBranch}" and (.end_ts == null))][0].start_ts'/

  def quayImageReady = false
  while(quayImageReady != true) {
    sleep(15)
    println "running time query"
    latestQuayTimestamp = sh(script: query, returnStdout: true)
    if(latestQuayTimestamp){
      try {
        def quayTime = new Date(Long.valueOf(latestQuayTimestamp) * 1000 )
        println "the latest commit time is "+commitTime
        println "the quay build time is "+quayTime

        if(quayTime > commitTime) {
          quayImageReady = true
        }
      }
      catch(Exception ex) {
        println("the image is not ready with exception "+ex.getMessage())
      }
    }
  }
}
