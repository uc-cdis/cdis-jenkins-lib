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
      stage('PH Fetch') {
        pipe.git.fetchAllRepos()
      }
      stage('WaitForQuayBuild') {
        // quayHelper.waitForBuild(serviceHelper.getService(config))
      }
      stage('SelectNamespace') {
        pipe.kube.selectAndLockNamespace()
      }
      stage('ModifyManifest') {
        pipe.kube.editManifestService()
      }
      stage('K8sDeploy') {
        // pipe.kube.deploy()
      }
      stagew('GenerateData') {
        echo "HELLO WORLD"
        pipe.test.simulateData(pipe.kube.kubectlNamespace)
      }
      stage('RunTests') {
        echo "RUNNNGG"
        pipe.test.runIntegrationTests(pipe.kube.kubectlNamespace, pipe.conf.service)
      }
    }

    // Post Pipeline steps
    def currentResult = currentBuild.result
    if ("UNSTABLE" == currentResult) {
      echo "Unstable!"
      //slack.sendUnstable()
    }
    else if ("FAILURE" == currentResult) {
      echo "Failure!"
      archiveArtifacts(artifacts: '**/output/*.png', fingerprint: true)
      //slack.sendFailure()
    }
    else if ("SUCCESS" == currentResult) {
      echo "Success!"
      //slack.sendSuccess()
    }

    // always unlock the namespace
    pipe.kube.klock('unlock')
    echo "done"
    junit "gen3-qa/output/*.xml"
  }
}