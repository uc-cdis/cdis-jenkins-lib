#!groovy

/**
* Get UID for current build
*
* @param config - pipeline configuration
* @returns uid - string
*/
def call(Map config) {
  return getServiceName(config)+"-"+"$env.GIT_BRANCH".replaceAll("/", "_")+"-"+env.BUILD_NUMBER
}
