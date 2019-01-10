import groovy.transform.Field

@Field def config

/**
* Constructor for tester
* Runs tests
*
* @param config - pipeline config
*/
def create(Map config) {
  this.config = config
  this.startedIntegrationTests = false

  return this
}

/**
* Provides context for running gen3-qa tasks
*
* @param namespace - namespace to run command in
* @param body - command(s) to run
*/
def gen3Qa(String namespace, Closure body) {
  withEnv(['GEN3_NOPROXY=true', "vpc_name=${namespace}", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "KUBECTL_NAMESPACE=${namespace}", "NAMESPACE=${namespace}", "TEST_DATA_PATH=$env.WORKSPACE/testData/"]) {
    return body()
  }
}

/**
* Runs gen3-qa integration tests
*
* @param namespace - namespace to run integration tests in
* @param service - name of service the test is being run for
*/
def runIntegrationTests(String namespace, String service) {
  this.startedIntegrationTests = true
  dir('gen3-qa') {
    gen3Qa(namespace, {
      sh "bash ./run-tests.sh $env.NAMESPACE --service=${service}"
    })
  }
}

/**
* Simulates data used in tests
*
* @param namespace - namespace to simulate data for
*/
def simulateData(String namespace) {
  dir('data-simulator') {
    gen3Qa(namespace, {
      sh "bash ./jenkins-simulate-data.sh ${namespace}"
    })
  }
}

def teardown() {
  if (this.startedIntegrationTests) {
    junit "gen3-qa/output/*.xml"
  }
}