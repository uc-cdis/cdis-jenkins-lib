#!groovy

def call(Map config) {
  stages {
    stage('TryLockNamespace') {
      steps {
        script {
          // try to lock particular namespace
        }
      }
    }
    stage('ModifyManifest') {
      steps {
        // modify manifest
      }
    }
  }
}
