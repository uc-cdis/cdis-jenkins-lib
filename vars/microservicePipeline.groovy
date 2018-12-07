#!groovy

/**
* Pipline for builing and testing microservices
* 
* @param config - pipeline configuration
*/
def call(Map config) {
  node {
    pipe = pipelineHelper.create(config)
    try {
      stage('FetchCode') {
        pipe.git.fetchAllRepos()
      }
      if (config.containsKey('') && config.waitForQuay) {
        stage('WaitForQuayBuild') {
          // pipe.quay.waitForBuild()
        }
      }
      stage('SelectNamespace') {
        pipe.kube.selectAndLockNamespace()
      }
      stage('ModifyManifest') {
        pipe.manifest.editService(pipe.kube.getHostname())
      }
      stage('K8sDeploy') {
        pipe.kube.deploy()
      }
      stage('GenerateData') {
        pipe.test.simulateData(pipe.kube.kubectlNamespace)
      }
      stage('RunTests') {
        pipe.test.runIntegrationTests(pipe.kube.kubectlNamespace, pipe.config.service)
      }
    }
    catch (e) {
      pipe.handleError(e)
    }
      finally {
      // Post Pipeline steps
      stage('Post') {
        pipe.teardown(currentBuild.result)
      }
    }
  }
}