#!groovy

/**
* If in a microservice pipeline, returns the service that triggered the build
*
* @param config - pipeline config
* @returns serviceName - name of service
*/
def call(Map config) {
  if (config && config.JOB_NAME) {
    return config.JOB_NAME
  }
  return "$env.JOB_NAME".split('/')[1]
}
