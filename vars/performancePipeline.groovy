#!groovy

import hudson.tasks.test.AbstractTestResultAction

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  node {
    kubectlNamespace = null
    kubeLocks = []
    pipeConfig = pipelineHelper.setupConfig(config)
    pipelineHelper.cancelPreviousRunningBuilds()
    try {
      stage('FetchCode') {
        gitHelper.fetchAllRepos(pipeConfig['currentRepoName'])
      }
      stage('FetchDataClient') {
        testHelper.fetchDataClient()
      }
      stage('SelectNamespace') {
        (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(pipeConfig['UID'], ["jenkins-perf"])
        kubeLocks << lock
      }
      stage('WaitForQuayBuild') {
        quayHelper.waitForBuild(
          pipeConfig['currentRepoName'],
          pipeConfig['currentBranchFormatted']
        )
      }
      stage('ModifyManifest') {
        manifestHelper.editService(
          kubeHelper.getHostname(kubectlNamespace),
          pipeConfig.serviceTesting.name,
          pipeConfig.serviceTesting.branch
        )
      }
      stage('K8sDeploy') {
        kubeLocks << kubeHelper.newKubeLock(kubectlNamespace, "gen3-reset", "reset-lock")
        kubeHelper.reset(kubectlNamespace)
      }
      stage('VerifyClusterHealth') {
        kubeHelper.waitForPods(kubectlNamespace)
        testHelper.checkPodHealth(kubectlNamespace)
      }
      stage('DownloadS3data') {
        copyS3('s3://cdis-terraform-state/regressions/dumps/', "$env.WORKSPACE/regressions/dumps/", '--recursive')
        copyS3('s3://cdis-terraform-state/regressions/subm/10/DataImportOrder.txt', "$env.WORKSPACE/testData/DataImportOrderPath.txt")
        dir('gen3-qa') {
          copyS3('s3://cdis-terraform-state/regressions/subm/10/DataImportOrder.txt', 'DataImportOrder.txt')
        }
      }
      stage("RunPerformanceTests") {
        dir('gen3-qa') {
          testHelper.gen3Qa(kubectlNamespace,
            { sh "bash ./run-performance-tests.sh ${namespace}" },
            ["TEST_DATA_PATH=''",
              "PROGRAM_SLASH_PROJECT=jnkns/jenkins"]
          )
        }
      }
    }
    catch (e) {
      pipelineHelper.handleError(e)
    }
    finally {
      stage('Post') {
        sh "rm -rf $env.WORKSPACE/regressions/dumps/"
        sh "rm -rf $env.WORKSPACE/testData/DataImportOrderPath.txt"
        dir('gen3-qa') {
          sh "rm -rf DataImportOrder.txt"
        }
        if (env.CHANGE_ID) {
          r = junitReport.junitReportTable()
          pullRequest.comment(r)
        }
        kubeHelper.teardown(kubeLocks)
        testHelper.teardown()
        // pipelineHelper.teardown(currentBuild.result)
      }
    }
  }
}
