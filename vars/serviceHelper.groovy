/**
* Returns the service that triggered the build
*
* @param config - pipeline config
* @returns serviceName - name of service
*/
def getService(Map config) {
  if (config && config.JOB_NAME) {
    return config.JOB_NAME
  }
  return "$env.JOB_NAME".split('/')[1]
}

/**
* Returns a UID for the current build
* Used when locking/unlocking namespaces
*
* @param config - pipeline config
* @returns uid
*/
def getUid(Map config) {
  branch = getBranch(config)
  return "${getService(config)}-${formatBranchName(branch)}-${env.BUILD_NUMBER}"
}

/**
* Replaces all / in branch name with _
* Used for creating UID and quay image names
*
* @param config - pipeline config
* @returns uid
*/
def formatBranchName(String branchName) {
  return "${branchName}".replaceAll("/", "_")
}

/**
* Returns branch using for build - gives priority to pipeline configuration
*
* @param config - pipeline config
* @returns branchName
*/
def getBranch(Map config) {
  if (config && config.GIT_BRANCH) {
    return config.GIT_BRANCH
  }
  echo "getBranch: asdfg: ${gitHelper.getGg()}"
  echo "getBranch: NO CONFIG. gitHelper.vars.GIT_BRANCH: ${gitHelper.vars.GIT_BRANCH}"
  echo "getBranch: NO CONFIG. Returning ${env.GIT_BRANCH}."
  return env.GIT_BRANCH
}