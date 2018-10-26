#!groovy

/**
* Pipline for building and testing microservices
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
      if (!pipe.config.skipQuay) {
        stage('WaitForQuayBuild') {
          pipe.quay.waitForBuild()
        }
      }
      stage('SelectNamespace') {
        pipe.kube.selectAndLockNamespace()
      }
      stage('ModifyManifest') {
        pipe.manifest.editService(
          pipe.kube.getHostname(),
          pipe.config.serviceTesting.name,
          pipe.config.serviceTesting.branch
        )
      }
      stage('K8sDeploy') {
        // pipe.kube.deploy()
        pipe.kube.reset()
      }
      stage('GenerateData') {
        pipe.test.simulateData(pipe.kube.kubectlNamespace)
      }
      stage('RunTests') {
        pipe.test.runIntegrationTests(
          pipe.kube.kubectlNamespace,
          pipe.config.serviceTesting.name
        )
      }
    }
    catch (e) {
      pipe.handleError(e)
    }
    finally {
      stage('Post') {
        pipe.teardown(currentBuild.result)
      }
    }
  }
}