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
  QUAY_API = 'https://quay.io/api/v1/repository/cdis/'
  url = "$QUAY_API"+repoName+"/tag"
  query = "curl -s "+timeUrl+/ |  jq '[.tags[]|select(.name=="${formattedBranch}" and (.end_ts == null))][0].start_ts'/

  def quayImageReady = false
  while(quayImageReady != true) {
    println "running time query"
    latestQuayTimestamp = sh(script: query, returnStdout: true).trim()

    DateFormat friendlyFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z");
    String commitTime = friendlyFormat.format(commitTimestamp);
    String quayTime = friendlyFormat.format(latestQuayTimestamp);

    if(quayTime > commitTime) {
      quayImageReady = true
    }
  }
}
