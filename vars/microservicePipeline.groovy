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
    testedEnv = "" // for manifest pipeline
    pipeConfig = pipelineHelper.setupConfig(config)
    pipelineHelper.cancelPreviousRunningBuilds()
    try {
      stage('FetchCode') {
        gitHelper.fetchAllRepos(pipeConfig['currentRepoName'])
      }
      if (pipeConfig.MANIFEST == null || pipeConfig.MANIFEST == false || pipeConfig.MANIFEST != "True") {
        // Setup stages for NON manifest builds
        stage('WaitForQuayBuild') {
          quayHelper.waitForBuild(
            pipeConfig['currentRepoName'],
            pipeConfig['currentBranchFormatted']
          )
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
      }

      if (pipeConfig.MANIFEST != null && (pipeConfig.MANIFEST == true || pipeConfig.MANIFEST == "True")) {
        // Setup stages for MANIFEST builds
        stage('SelectNamespace') {
          (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(pipeConfig['UID'])
          kubeLocks << lock
        }
        stage('ModifyManifest') {
          testedEnv = manifestHelper.manifestDiff(kubectlNamespace)
        }
      }

      stage('K8sReset') {
        // adding the reset-lock lock in case reset fails before unlocking
        kubeLocks << kubeHelper.newKubeLock(kubectlNamespace, "gen3-reset", "reset-lock")
        kubeHelper.reset(kubectlNamespace)
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
        testHelper.runIntegrationTests(
          kubectlNamespace,
          pipeConfig.serviceTesting.name,
          testedEnv
        )
      }
      stage('CleanS3') {
        testHelper.cleanS3()
      }
    }
    catch (e) {
      pipelineHelper.handleError(e)
    }
    finally {
      stage('Post') {
        kubeHelper.teardown(kubeLocks)
        testHelper.teardown()
        pipelineHelper.teardown(currentBuild.result)
      }
    }
  }
}