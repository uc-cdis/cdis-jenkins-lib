/**
* Updates config by adding some missing and new values to the map
*
* @param config - pipeline config
*/
def setupConfig(Map config) {
  // get git info of repo/branch that triggered build
  if (!env.CHANGE_BRANCH) {
    config.currentBranchFormatted = "${env.BRANCH_NAME}"
  } else {
    config.currentBranchFormatted = "${env.CHANGE_BRANCH}".replaceAll("/", "_")
  }

  config.currentRepoName = "$env.JOB_NAME"
  if ("$env.JOB_NAME".contains("perf")) {
    config.currentRepoName = "$env.JOB_NAME".split('-')[1].split('/')[0]
  } else {
    config.currentRepoName = "$env.JOB_NAME".split('/')[1]
  }

  // update config with the service we are testing
  // if no info is provided, we assume we are testing the current repository and branch
  if (null == config || !config.containsKey('serviceTesting')) {
    config.serviceTesting = [name: config.currentRepoName, branch: config.currentBranchFormatted]
  }

  config.UID = "${config.currentRepoName}-${config.currentBranchFormatted}-${env.BUILD_NUMBER}"

  return config
}

/**
* Cancel all previous builds that are currently running
* Source: https://issues.jenkins-ci.org/browse/JENKINS-43353?focusedCommentId=294556&page=com.atlassian.jira.plugin.system.issuetabpanels%3Acomment-tabpanel#comment-294556
* Note: Currently using @NonCPS decorator b/c I and others cant
* seem to get it to work in var/ of shared libararies (https://groups.google.com/forum/#!topic/jenkinsci-users/fbkCThpjGv8)
*/
def cancelPreviousRunningBuilds() {
  maxBuildsToSearch = 20
  b = currentBuild
  for (int i=0; i<maxBuildsToSearch; i++) {
    b = b.getPreviousBuild();
    if (b == null) break;
    rawBuild = b.rawBuild
    if (rawBuild.isBuilding()) {
      println("Stopping build: ${rawBuild}")
      rawBuild.doStop()
    }
    rawBuild = null // make null to keep pipeline serializable
  }
  b = null // make null to keep pipeline serializable
}

/**
* Generic error handler, prints error message and stacktrace
*
* @param e - Exception/Error to handle
*/
def handleError(e) {
  def st = new StringWriter()
  e.printStackTrace(new PrintWriter(st))
  echo "ERROR: ${e.message}\n\nStackTrace:\n${st}"
  throw e
}

/**
* Procedure to run after pipline succeeds or fails
*
* @param buildResult - the current build result (accessible by currentBuild.result)
*/
def teardown(String buildResult) {
  archiveArtifacts(artifacts: '**/output/*.png', fingerprint: true)
  if ("UNSTABLE" == buildResult) {
    echo "Build Unstable!"
  }
  else if ("FAILURE" == buildResult) {
    echo "Build Failure!"
  }
  else if ("SUCCESS" == buildResult) {
    echo "Build Success!"
  }
}