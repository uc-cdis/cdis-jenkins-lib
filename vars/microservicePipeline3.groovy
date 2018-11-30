#!groovy

/**
* Pipline for builing and testing microservices
* 
* @param config - pipeline configuration
*/
def call(Map config) {
  node {
    try {
      pipe = pipelineHelper.create(config)
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
        pipe.kube.editManifest()
      }
      stage('K8sDeploy') {
        pipe.kube.deploy()
      }
      stage('RunTests') {
        testHelper.runIntegrationTests()
      }
    }
    catch (e) {
      // something failed. do something about it?
      throw e
    }
    finally {
      def currentResult = currentBuild.result ?: 'SUCCESS'
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
}