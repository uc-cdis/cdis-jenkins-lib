#!groovy

/**
* Pipline for building and testing microservices
* 
* @param config - pipeline configuration
*/
def call(Map config) {
  node {
    kubectlNamespace = null
    kubeLocks = []
    pipeConfig = pipelineHelper.setupConfig(config)
    try {
      stage('FetchCode') {
        gitHelper.fetchAllRepos(pipeConfig['currentRepoName'])
      }
      if (!pipeConfig.skipQuay) {
        stage('WaitForQuayBuild') {
          quayHelper.waitForBuild(
            pipeConfig['currentRepoName'],
            pipeConfig['currentBranchFormatted'],
            env.GIT_COMMIT
          )
        }
      }
      stage('SelectNamespace') {
        (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(pipeConfig['UID'])
        kubeLocks << lock
      }
      // stage('ModifyManifest') {
      //   pipe.manifest.editService(
      //     kubeHelper.getHostname(kubectlNamespace),
      //     pipeConfig.serviceTesting.name,
      //     pipeConfig.serviceTesting.branch
      //   )
      // }
      // stage('K8sReset') {
      //   kubeHelper.reset(kubectlNamespace)
      // }
      stage('VerifyClusterHealth') {
        kubeHelper.waitForPods(kubectlNamespace)
        // pipe.test.checkPodHealth(kubectlNamespace)
      }
      // stage('GenerateData') {
      //   pipe.test.simulateData(kubectlNamespace)
      // }
      // stage('FetchDataClient') {
      //   pipe.test.fetchDataClient()
      // }
      // stage('RunTests') {
      //   // pipe.test.runIntegrationTests(
      //   //   pipe.kube.kubectlNamespace,
      //   //   pipe.config.serviceTesting.name
      //   // )
      //   pipe.test.runIntegrationTests(
      //     kubectlNamespace,
      //     pipeConfig.serviceTesting.name
      //   )
      // }
    }
    catch (e) {
      // pipe.handleError(e)
      pipelineHelper.handleError(e)
    }
    finally {
      stage('Post') {
        // pipe.teardown(currentBuild.result)
        kubeHelper.teardown(kubeLocks)
        pipelineHelper.teardown(currentBuild.result)
      }
    }
  }
}