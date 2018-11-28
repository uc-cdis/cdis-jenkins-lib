#!groovy

/**
* Pipline for builing and testing microservices
* 
* @param config - pipeline configuration
*/
def call(Map config) {
  node {
    stage('FetchCode') {
      gitHelper.fetchAllRepos()
    }
    stage('WaitForQuayBuild') {
      // quayHelper.waitForBuild(serviceHelper.getService(config))
    }
    stage('SelectNamespace') {
      kubeHelper.selectAndLockNamespace(config.namespaces, serviceHelper.getUid(config))
    }
    stage('ModifyManifest') {
      kubeHelper.editManifest(serviceHelper.getService(config))
    }
    stage('K8sDeploy') {
      kubeHelper.deploy()
    }
    stage('RunTests') {
      testHelper.runIntegrationTests()
    }
  }
  post {
    success {
      echo "Success!"
      //slack.sendSuccess()
    }
    failure {
      echo "Failure!"
      archiveArtifacts artifacts: '**/output/*.png', fingerprint: true
      //slack.sendFailure()
    }
    unstable {
      echo "Unstable!"
      //slack.sendUnstable()
    }
    always {
      script {
        kubeHelper.klock('unlock', serviceHelper.getUid(config) )
      }
      echo "done"
      junit "gen3-qa/output/*.xml"
    }
  }
}