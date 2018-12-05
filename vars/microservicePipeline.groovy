#!groovy

/**
* Pipline for builing and testing microservices
* 
* @param config - pipeline configuration
*/
def call(Map config) {
  node {
    pipe = pipelineHelper.create(config)
    catchError {
      stage('FetchCode') {
        pipe.git.fetchAllRepos()
      }
      stage('WaitForQuayBuild') {
        // pipe.quay.waitForBuild()
      }
      stage('SelectNamespace') {
        pipe.kube.selectAndLockNamespace()
      }
      stage('ModifyManifest') {
        pipe.manifest.editService(pipe.kube.getHostname())
      }
      stage('K8sDeploy') {
        pipe.kube.deploy()
      }
      stage('GenerateData') {
        pipe.test.simulateData(pipe.kube.kubectlNamespace)
      }
      stage('RunTests') {
        pipe.test.runIntegrationTests(pipe.kube.kubectlNamespace, pipe.config.service)
      }
    }

    // Post Pipeline steps
    def currentResult = currentBuild.result
    if ("UNSTABLE" == currentResult) {
      echo "Unstable!"
      // slack.sendUnstable()
    }
    else if ("FAILURE" == currentResult) {
      echo "Failure!"
      archiveArtifacts(artifacts: '**/output/*.png', fingerprint: true)
      // slack.sendFailure()
    }
    else if ("SUCCESS" == currentResult) {
      echo "Success!"
      // slack.sendSuccess()
    }

    // unlock the namespace
    pipe.kube.klock('unlock')
    echo "done"
    junit "gen3-qa/output/*.xml"
  }
}