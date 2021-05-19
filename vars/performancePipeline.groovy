#!groovy

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  // between 8 PM to 8 AM CDT
  properties([pipelineTriggers([cron(env.BRANCH_NAME == 'master' ? 'H 1-13 * * *' : '')])])

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
          pipeConfig['quayRegistry'],
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
        kubeHelper.teardown(kubeLocks)
        testHelper.teardown(false)

        if (env.CHANGE_ID) {
          def r = junitReport.junitReportTable()
          pullRequest.comment(r)
        }
      }
    }
  }
}
