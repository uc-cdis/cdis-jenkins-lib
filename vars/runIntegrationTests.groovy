#!groovy

// Runs integration tests after simulating data with the data simulator
def call() {
  steps {
    dir('gen3-qa') {
      withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=$env.WORKSPACE/testData/"]) {
        sh "bash ./jenkins-simulate-data.sh $env.KUBECTL_NAMESPACE"
        sh "bash ./run-tests.sh $env.KUBECTL_NAMESPACE"
      }
    }
  }
}
