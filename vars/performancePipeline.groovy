#!groovy

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  kubectlNamespace = null
  kubeLocks = []
  pipeConfig = pipelineHelper.setupConfig(config)
  pipelineHelper.cancelPreviousRunningBuilds()

  node {
    try {
      stage('FetchCode') {
        gitHelper.fetchAllRepos(pipeConfig['currentRepoName'])
      }
      stage('FetchDataClient') {
        testHelper.fetchDataClient()
      }
      stage('SelectNamespace') {
        (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(['jenkins-perf'] as String[])
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
        copyS3('s3://cdis-terraform-state/regressions/psql_dumps/', 'regressions/psql_dumps/')
        copyS3('s3://cdis-terraform-state/regressions/subm_100/DataImportOrder.txt', "$env.WORKSPACE/testData/DataImportOrderPath.txt")
        dir('gen3-qa') {
          copyS3('s3://cdis-terraform-state/regressions/subm_100/DataImportOrder.txt', 'DataImportOrder.txt')
        }
      }

      for (db in [10, 100]) {
        stage("Submission${db}") {
          restoreDbDump('regressions/psql_dumps/${db}_psql.sql')
          dir('gen3-qa') {
            sh "git checkout feat/regression-pipeline"
            withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=''"]) {
              sh "bash ./run-regressions.sh $env.KUBECTL_NAMESPACE --service=$env.service"
            }
          }
        }
      }

      for (db in [10, 100, 1000]) {
        stage("Query${db}") {
          restoreDbDump('regressions/psql_dumps/${db}_psql.sql')
          dir('gen3-qa') {
            withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=''", "PROGRAM_SLASH_PROJECT=jnkns/jenkins"]) {
              sh "bash ./run-queries.sh $env.KUBECTL_NAMESPACE --service=$env.service"
            }
          }
        }
      }

      for (db in [10, 100]) {
        stage("Export${db}") {
          restoreDbDump('regressions/psql_dumps/${db}_psql.sql')
          dir('gen3-qa') {
            sh "git checkout feat/regression-pipeline"
            withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=''"]) {
              sh "bash ./run-export.sh $env.KUBECTL_NAMESPACE --service=$env.service"
            }
          }
        }
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
