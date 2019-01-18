#!groovy

/**
* Pipline for building and testing microservices
* 
* @param config - pipeline configuration
*/
def call(Map config) {
  node {
    pipe = pipelineHelper.create(config)
    def kubectlNamespace = null
    def kubeLocks = []
    pipeConfig = pipelineHelper.setupConfig(config)
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
        // pipe.kube.selectAndLockNamespace()
        (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(lockOwner=pipeConfig.UID)
        kubeLocks << lock
      }
      stage('ModifyManifest') {
        // pipe.manifest.editService(
        //   pipe.kube.getHostname(),
        //   pipe.config.serviceTesting.name,
        //   pipe.config.serviceTesting.branch
        // )
        pipe.manifest.editService(
          kubeHelper.getHostname(kubectlNamespace),
          pipeConfig.serviceTesting.name,
          pipeConfig.serviceTesting.branch
        )
      }
      stage('K8sReset') {
        // pipe.kube.reset()
        kubeHelper.reset(kubectlNamespace)
      }
      stage('VerifyClusterHealth') {
        // pipe.kube.waitForPods()
        kubeHelper.waitForPods(kubectlNamespace)
        pipe.test.checkPodHealth(kubectlNamespace)
      }
      stage('GenerateData') {
        // pipe.test.simulateData(pipe.kube.kubectlNamespace)
        pipe.test.simulateData(kubectlNamespace)
      }
      stage('FetchDataClient') {
        pipe.test.fetchDataClient()
      }
      stage('RunTests') {
        // pipe.test.runIntegrationTests(
        //   pipe.kube.kubectlNamespace,
        //   pipe.config.serviceTesting.name
        // )
        pipe.test.runIntegrationTests(
          kubectlNamespace,
          pipeConfig.serviceTesting.name
        )
      }
    }
    catch (e) {
      // pipe.handleError(e)
      pipeHelper.handleError(e)
    }
    finally {
      stage('Post') {
        // pipe.teardown(currentBuild.result)
        kubeHelper.teardown(kubeLocks)
        pipeHelper.teardown(currentBuild.result)
      }
    }
  }
}
