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
        def currentBranchFormatted = pipeConfig['currentBranchFormatted']
        if (currentBranchFormatted.length() > 63) {
            def newCurrentBranchFormatted = currentBranchFormatted.substring(0,63)
            println("### ## currentBranchFormatted \"${currentBranchFormatted}\" is longer than 63 characters. It will will be truncated to \"${newCurrentBranchFormatted}\"")
            currentBranchFormatted = newCurrentBranchFormatted
            def validImageTagName = currentBranchFormatted ==~ /^(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?$/
            if (!validImageTagName) {
                if (currentBranchFormatted.endsWith("-") || currentBranchFormatted.endsWith(".") || currentBranchFormatted.endsWith("_")) {
                    newCurrentBranchFormatted = currentBranchFormatted.substring(0,currentBranchFormatted.length()-1)+"0"
                    validImageTagName = newCurrentBranchFormatted ==~ /^(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?$/
                    if (validImageTagName) {
                        println("### ## currentBranchFormatted \"${currentBranchFormatted}\" violates validation, it will be changed to \"${newCurrentBranchFormatted}\"")
                        currentBranchFormatted = newCurrentBranchFormatted
                    } else {
                        throw new Exception("currentBranchFormatted \"${currentBranchFormatted}\" violates validation and cannot be used")
                    }
                } else {
                    throw new Exception("currentBranchFormatted \"${currentBranchFormatted}\" violates validation and cannot be used")
                }
            } else {
                println("### ## currentBranchFormatted \"${currentBranchFormatted}\" passes validation")
            }
        }
        println("### ## currentBranchFormatted: ${currentBranchFormatted}")
        quayHelper.waitForBuild(
          pipeConfig['quayRegistry'],
          currentBranchFormatted
        )
      }
      stage('ModifyManifest') {
        def currentBranchFormatted = pipeConfig.serviceTesting.branch
        if (currentBranchFormatted.length() > 63) {
            def newCurrentBranchFormatted = currentBranchFormatted.substring(0,63)
            println("### ## currentBranchFormatted \"${currentBranchFormatted}\" is longer than 63 characters. It will will be truncated to \"${newCurrentBranchFormatted}\"")
            currentBranchFormatted = newCurrentBranchFormatted
            def validImageTagName = currentBranchFormatted ==~ /^(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?$/
            if (!validImageTagName) {
                if (currentBranchFormatted.endsWith("-") || currentBranchFormatted.endsWith(".") || currentBranchFormatted.endsWith("_")) {
                    newCurrentBranchFormatted = currentBranchFormatted.substring(0,currentBranchFormatted.length()-1)+"0"
                    validImageTagName = newCurrentBranchFormatted ==~ /^(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?$/
                    if (validImageTagName) {
                        println("### ## currentBranchFormatted \"${currentBranchFormatted}\" violates validation, it will be changed to \"${newCurrentBranchFormatted}\"")
                        currentBranchFormatted = newCurrentBranchFormatted
                    } else {
                        throw new Exception("currentBranchFormatted \"${currentBranchFormatted}\" violates validation and cannot be used")
                    }
                } else {
                    throw new Exception("currentBranchFormatted \"${currentBranchFormatted}\" violates validation and cannot be used")
                }
            } else {
                println("### ## currentBranchFormatted \"${currentBranchFormatted}\" passes validation")
            }
        }
        println("### ## currentBranchFormatted: ${currentBranchFormatted}")
        manifestHelper.editService(
          kubeHelper.getHostname(kubectlNamespace),
          pipeConfig.serviceTesting.name,
          currentBranchFormatted
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
