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
* Provides context for running gen3-qa tasks
*
* @param namespace - namespace to run command in
* @param body - command(s) to run
*/
def gen3Qa(String namespace, Closure body) {
  dir('gen3-qa') {
    withEnv(['GEN3_NOPROXY=true', "vpc_name=${namespace}", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "KUBECTL_NAMESPACE=${namespace}", "NAMESPACE=${namespace}", "TEST_DATA_PATH=$env.WORKSPACE/testData/"]) {
      return body()
    }
  }
}

/**
* Runs gen3-qa integration tests
*
* @param namespace - namespace to run integration tests in
* @param service - name of service the test is being run for
*/
def runIntegrationTests(String namespace, String service) {
  gen3Qa(namespace, {
    sh "bash ./run-tests.sh $env.NAMESPACE --service=${service}"
  })
}

/**
* Simulates data used in tests
*
* @param namespace - namespace to simulate data for
*/
def simulateData(String namespace) {
  gen3Qa(namespace, {
    sh "bash ./jenkins-simulate-data.sh ${namespace}"
  })
}
