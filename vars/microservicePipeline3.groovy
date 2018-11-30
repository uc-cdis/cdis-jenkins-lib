#!groovy

/**
* Pipline for builing and testing microservices
* 
* @param config - pipeline configuration
*/
def call(Map config) {
  node {
    ph = pipelineHelper.new()
    stage('PH Fetch') {
      ph.git.fetchAllRepos()
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