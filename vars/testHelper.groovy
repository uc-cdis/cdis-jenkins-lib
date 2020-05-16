/**
* Provides context for running gen3-qa tasks
*
* @param namespace - namespace to run command in
* @param body - command(s) to run
*/
def gen3Qa(String namespace, Closure body, List<String> add_env_variables = []) {
  env_variables = ["GEN3_NOPROXY=true",
    "vpc_name=qaplanetv1",
    "GEN3_HOME=$env.WORKSPACE/cloud-automation",
    "KUBECTL_NAMESPACE=${namespace}",
    "NAMESPACE=${namespace}",
    "TEST_DATA_PATH=$env.WORKSPACE/testData/",
    "DATA_CLIENT_PATH=$env.WORKSPACE"]

  if (add_env_variables) {
    env_variables += add_env_variables
  }

  withEnv(env_variables) {
    return body()
  }
}

/**
* Runs gen3-qa integration tests
*
* @param namespace - namespace to run integration tests in
* @param service - name of service the test is being run for
* @param testedEnv - environment the test is being run for (for manifest PRs)
*/
def runIntegrationTests(String namespace, String service, String testedEnv, String isGen3Release, String selectedTest="all") {
  dir('gen3-qa') {
    gen3Qa(namespace, {
      // clean up old test artifacts in the workspace
      sh "/bin/rm -rf output/ || true"
      sh "mkdir output"
      testResult = sh(script: "bash ./run-tests.sh ${namespace} --service=${service} --testedEnv=${testedEnv} --isGen3Release=${isGen3Release} --selectedTest=${selectedTest}", returnStatus: true);
      if (testResult == 0) {
        // if the test succeeds, then verify that we got some test results ...
        testResult = sh(script: "ls output/ | grep '.*\\.xml'", returnStatus: true)
      }
      dir('output') {
        // collect and archive service logs
        echo "Archiving service logs via 'gen3 logs snapshot'"
        sh(script: "bash ${env.WORKSPACE}/cloud-automation/gen3/bin/logs.sh snapshot", returnStatus: true)
      }
      if (testResult != 0) {
        currentBuild.result = 'ABORTED'
        error("aborting build - testsuite failed")
      }
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
* Clean S3
*/
def cleanS3() {
  qaBucket = "qaplanetv1-data-bucket"
  cleanUpDir = "~/s3-cleanup"
  localCopy = "$env.WORKSPACE/cleanup-copy.txt"

  filesList = sh(
    // no error if the dir does not exist or is empty
    script: "ls -d $cleanUpDir/* || true",
    returnStdout: true
  )

  // each file contains a list of GUIDs to delete in s3
  for (filePath in filesList.readLines()) {
    // move the file to the current workspace so that other jenkins
    // sessions will not try to use it to clean up
    try {
      sh "mv $filePath $localCopy || true"
      fileContents = new File(localCopy).text

      for (guid in fileContents.readLines()) {
        // if the file does not exist, no error is thrown
        sh "aws s3 rm --recursive s3://$qaBucket/$guid"
      }

      sh "rm $localCopy"
    }
    catch (FileNotFoundException e) {
      // if we can't move it, another session is using it: do nothing
    }
  }
}

/**
* Delete Service Account keys from the dcf-integration Google Cloud Platform project
* Lingering keys are causing intermittent failures in the CI tests
*/
def deleteGCPServiceAccountKeys(jenkinsNamespace) {
  withCredentials([file(credentialsId: 'fence-google-app-creds-secret', variable: 'MY_SECRET_GCLOUD_APP_CREDENTIALS_FILE')]) {
    sh '''
      mv $MY_SECRET_GCLOUD_APP_CREDENTIALS_FILE fence_google_app_creds_secret.json
      gcloud auth activate-service-account --key-file fence_google_app_creds_secret.json
    '''
    def SELECTED_JENKINS_NAMESPACE = jenkinsNamespace;
    def JPREFIX="";

    switch(SELECTED_JENKINS_NAMESPACE) {
    case "jenkins-dcp":
      println("deleting jdcp keys");
      JPREFIX="jdcp"
      break;
    case "jenkins-brain":
      println("deleting jbrain keys");
      JPREFIX="jbrain"
      break;
    case "jenkins-blood":
      println("deleting jblood keys");
      JPREFIX="jblood"
      break;
    case "jenkins-genomel":
      println("deleting jgmel keys");
      JPREFIX="jgmel"
      break;
    case "jenkins-niaid":
      println("deleting jniaid keys");
      JPREFIX="jniaid"
      break;
    default:
      println("invalid jenkins namespace: " + SELECTED_JENKINS_NAMESPACE);
      break;
    }

    def svc_accounts = ['JPREFIX-cdisautotestgmailcom-6@dcf-integration.iam.gserviceaccount.com', 'JPREFIX-cdisautotestgmailcom-7@dcf-integration.iam.gserviceaccount.com', 'JPREFIX-cdisautotestgmailcom-8@dcf-integration.iam.gserviceaccount.com']

    for(String sa: svc_accounts) {
      sa = sa.replace("JPREFIX", JPREFIX)
      println("deleting keys for ${sa}...");
      def sa_keys = sh(script: "gcloud iam service-accounts keys list --iam-account $sa", returnStdout: true)

      key_rows = sa_keys.split("\n");
      for (int i = 0; i < key_rows.length; i++) {
        // Skipe headers
        if (i == 0) continue
        println("key row: " + key_rows[i])
        def key_id = key_rows[i].split(" ")[0]
        def deletion_result = sh(script: "gcloud iam service-accounts keys delete $key_id --iam-account $sa --quiet", returnStdout: true, returnStatus:true)
        println(deletion_result)
      }
    }
    println("The GCP keys correspondent to this jenkins namespace have been deleted.");
  }
}

/**
* Verify pods are health
*/
def checkPodHealth(String namespace, String testedEnv) {
  dir('gen3-qa') {
    gen3Qa(namespace, {
      sh "bash ./check-pod-health.sh $testedEnv"
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
    echo "WARNING: Got the following exception when parsing junit test result:\n${e.message}\n\nStackTrace:\n${st}"
  }
}
