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
      stage('ModifyManifest') {
        manifestHelper.editService(
          kubeHelper.getHostname(kubectlNamespace),
          pipeConfig.serviceTesting.name,
          pipeConfig.serviceTesting.branch
        )
      }
      stage('K8sReset') {
        // adding the gen3-reset lock in case reset fails before unlocking
        kubeLocks << new kubeHelper.KubeLock(lockName: "reset-lock", lockOwner: "gen3-reset", kubectlNamespace: kubectlNamespace)
        // kubeHelper.reset(kubectlNamespace)
      }
      stage('VerifyClusterHealth') {
        kubeHelper.waitForPods(kubectlNamespace)
        testHelper.checkPodHealth(kubectlNamespace)
      }
      stage('GenerateData') {
        testHelper.simulateData(kubectlNamespace)
      }
      stage('FetchDataClient') {
        // we get the data client from master, unless the service being
        // tested is the data client itself, in which case we get the
        // executable for the current branch
        dataCliBranch = "master"
        if (pipeConfig.currentRepoName == "cdis-data-client") {
          dataCliBranch = env.CHANGE_BRANCH
        }
        testHelper.fetchDataClient(dataCliBranch)
      }
      stage('RunTests') {
        // testHelper.runIntegrationTests(
        //   kubectlNamespace,
        //   pipeConfig.serviceTesting.name
        // )
      }
    }
    catch (e) {
      pipelineHelper.handleError(e)
    }
    finally {
      stage('Post') {
        kubeHelper.teardown(kubeLocks)
        pipelineHelper.teardown(currentBuild.result)
      }
    }
  }
}