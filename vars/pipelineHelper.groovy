/**
* Updates config by adding some missing and new values to the map
*
* @param config - pipeline config
*/
def setupConfig(Map config) {
  // get git info of repo/branch that triggered build
  config.gitVars = checkout(scm)
  config.currentBranchFormatted = "${env.CHANGE_BRANCH}".replaceAll("/", "_")
  config.currentRepoName = "$env.JOB_NAME".split('/')[1]

  // update config with the service we are testing
  // if no info is provided, we assume we are testing the current repository and branch
  if (null == config || !config.containsKey('serviceTesting')) {
    config.serviceTesting = [name: config.currentRepoName, branch: config.currentBranchFormatted]
  }

  config.UID = "${config.currentRepoName}-${config.currentBranchFormatted}-${env.BUILD_NUMBER}"

  return config
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
  if ("UNSTABLE" == buildResult) {
    echo "Unstable!"
    // slack.sendUnstable()
  }
  else if ("FAILURE" == buildResult) {
    echo "Failure!"
    archiveArtifacts(artifacts: '**/output/*.png', fingerprint: true)
    // slack.sendFailure()
  }
  else if ("SUCCESS" == buildResult) {
    echo "Success!"
    // slack.sendSuccess()
  }
}