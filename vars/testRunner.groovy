/**
* Constructor for tester
* Runs tests
*
* @param config - pipeline config
*/
def create(Map config) {
  conf = config
  return this
}

/**
* Runs gen3-qa integration tests
*/
def runIntegrationTests(String namespace) {
  dir('gen3-qa') {
    withEnv(['GEN3_NOPROXY=true', "vpc_name=${namespace}", "GEN3_HOME=${env.WORKSPACE}/cloud-automation", "NAMESPACE=${namespace}", "TEST_DATA_PATH=$env.WORKSPACE/testData/"]) {
      sh "bash ./jenkins-simulate-data.sh $env.NAMESPACE"
      sh "bash ./run-tests.sh $env.NAMESPACE"
    }
  }
}

