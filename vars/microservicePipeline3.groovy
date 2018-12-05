#!groovy

/**
* Pipline for builing and testing microservices
* 
* @param config - pipeline configuration
*/
def call(Map config) {
  node {
    pipe = pipelineHelper.create(config)
    try {
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
      stage('RunTests') {
        x = pipe.kube.getNamespace()
        echo "ASDFG: $x"
        pipe.test.runIntegrationTests(x)
      }
    }
    catch (e) {
      // something failed. do something about it?
      echo "ERROR: $e"
      println(e.toString());
      println(e.getMessage());
      println(e.getStackTrace());

      throw(e)
    }
    finally {
      def currentResult = currentBuild.result ?: 'SUCCESS'
      println("CURREENT RESULT: ${currentResult}")
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