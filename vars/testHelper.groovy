/**
* Provides context for running gen3-qa tasks
*
* @param namespace - namespace to run command in
* @param body - command(s) to run
*/
def gen3Qa(String namespace, Closure body) {
  withEnv(['GEN3_NOPROXY=true', "vpc_name=qaplanetv1", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "KUBECTL_NAMESPACE=${namespace}", "NAMESPACE=${namespace}", "TEST_DATA_PATH=$env.WORKSPACE/testData/", "DATA_CLIENT_PATH=$env.WORKSPACE"]) {
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
  dir('gen3-qa') {
    gen3Qa(namespace, {
      sh "bash ./run-tests.sh ${namespace} --service=${service}"
    })
  }
}

/**
* Simulates data used in tests
*
* @param namespace - namespace to simulate data for
*/
def simulateData(String namespace) {
  dir('gen3-qa') {
    gen3Qa(namespace, {
      sh "bash ./jenkins-simulate-data.sh ${namespace}"
    })
  }
}

/**
* Fetches data client
*/
def fetchDataClient(String dataClientBranch="master") {
  dir('dataclient') {
    // Note: the data client does not use Jenkins yet (see PXP-2211)
    branch = dataClientBranch

    // Note: at this time, tests are always run on linux
    os = "linux"

    // download the gen3 data client executable from S3
    download_location = "dataclient.zip"
    sh String.format("aws s3 cp s3://cdis-dc-builds/%s/dataclient_%s.zip %s", branch, os, download_location)
    assert fileExists(download_location)
    unzip(download_location)

    // make sure we can execute it
    executable_name = "gen3-client"
    assert fileExists(executable_name)
    sh "mv ${executable_name} $env.WORKSPACE/${executable_name}"
    sh "chmod u+x $env.WORKSPACE/${executable_name}"
    sh "$env.WORKSPACE/${executable_name} --version"

    println "Data client successfully set up at: $env.WORKSPACE/${executable_name}"
  }
}

/**
* Verify pods are health
*/
def checkPodHealth(String namespace) {
  dir('gen3-qa') {
    gen3Qa(namespace, {
      sh "bash ./check-pod-health.sh"
    })
  }
}

def teardown() {
  // try to analyze junit files
  // result xml file may not exist due to pipeline or test suite failures
  try {
    junit "gen3-qa/output/*.xml"
  }
  catch(e) {
    def st = new StringWriter()
    e.printStackTrace(new PrintWriter(st))
    echo "WARNING: ${e.message}\n\nStackTrace:\n${st}"
  }
}