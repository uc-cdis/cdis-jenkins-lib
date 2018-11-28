#!groovy

/**
* Get branch name reformatted for Quay
*
* @param config - pipeline configuration
* @returns uid - string
*/
def call(Map config) {
  if (config && config.GIT_BRANCH) {
    return config.GIT_BRANCH
  }
  return "$env.GIT_BRANCH".replaceAll("/", "_")
}