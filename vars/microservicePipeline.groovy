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
      stage('K8sReset') {
        pipe.kube.reset()
      }
      stage('VerifyClusterHealth') {
        pipe.kube.waitForPods()
        pipe.test.checkPodHealth(pipe.kube.kubectlNamespace)
      }
      stage('GenerateData') {
        pipe.test.simulateData(pipe.kube.kubectlNamespace)
      }
      stage('FetchDataClient') {
        pipe.test.fetchDataClient()
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
