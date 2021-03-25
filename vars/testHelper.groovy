/**
* Provides context for running gen3-qa tasks
*
* @param namespace - namespace to run command in
* @param body - command(s) to run
*/
def gen3Qa(String namespace, Closure body, List<String> add_env_variables = []) {
  def PR_NUMBER = env.BRANCH_NAME.split('-')[1];
  def REPO_NAME = env.JOB_NAME.split('/')[1];
  def vpc_name = sh(script: "kubectl get cm --namespace ${namespace} global -o jsonpath=\"{.data.environment}\"", returntdout: true);
  env_variables = ["GEN3_NOPROXY=true",
    "PR_NUMBER=${PR_NUMBER}",
    "REPO_NAME=${REPO_NAME}",
    "vpc_name=${vpc_name}",
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
* Soon to be legacy function that runs gen3-qa integration tests sequentially (pfft... what a loser)
*
* @param namespace - namespace to run integration tests in
* @param service - name of service the test is being run for
* @param testedEnv - environment the test is being run for (for manifest PRs)
*/
def soonToBeLegacyRunIntegrationTests(String namespace, String service, String testedEnv, String isGen3Release,  List<String> selectedTests = ['all']) {
  withCredentials([
    usernamePassword(credentialsId: 'ras-test-user1-for-ci-tests', usernameVariable: 'RAS_TEST_USER_1_USERNAME', passwordVariable: 'RAS_TEST_USER_1_PASSWORD'),
    usernamePassword(credentialsId: 'ras-test-user2-for-ci-tests', usernameVariable: 'RAS_TEST_USER_2_USERNAME', passwordVariable: 'RAS_TEST_USER_2_PASSWORD')
  ]) {
    dir('gen3-qa') {
      gen3Qa(namespace, {
        // clean up old test artifacts in the workspace
        sh "/bin/rm -rf output/ || true"
        sh "mkdir output"
        testResult = null
        List<String> failedTestSuites = [];
        TestSuitesNonZeroStatusCodes = [];
        selectedTests.each {selectedTest ->
          testResult = sh(script: "bash ./run-tests.sh ${namespace} --service=${service} --testedEnv=${testedEnv} --isGen3Release=${isGen3Release} --selectedTest=${selectedTest}", returnStatus: true);
          if (testResult != 0){
            TestSuitesNonZeroStatusCodes.add(testResult)
            //TODO: get which test suite is failed
            //TODO: using test suite and label map to replace XML parsing
          }
        }
        // check XMLs inside the output folder
        failedTestSuites = xmlHelper.identifyFailedTestSuites()
        def featureLabelMap = xmlHelper.assembleFeatureLabelMap(failedTestSuites)
        
        if (TestSuitesNonZeroStatusCodes.size() == 0) {
          // if the test succeeds, then verify that we got some test results ...
          testResult = sh(script: "ls output/ | grep '.*\\.xml'", returnStatus: true)
          if (testResult != 0){
            TestSuitesNonZeroStatusCodes.add(testResult)
          }
        }
        dir('output') {
          // collect and archive service logs
          echo "Archiving service logs via 'gen3 logs snapshot'"
          sh(script: "bash ${env.WORKSPACE}/cloud-automation/gen3/bin/logs.sh snapshot", returnStatus: true)
        }
        def successMsg = "Successful CI run for https://github.com/uc-cdis/$REPO_NAME/pull/$PR_NUMBER :tada:"
        def commonMsg = "Duration: ${currentBuild.durationString} :clock1:\n"
        if (TestSuitesNonZeroStatusCodes.size() != 0) {
          def failureMsg = "CI Failure on https://github.com/uc-cdis/$REPO_NAME/pull/$PR_NUMBER :facepalm: \n"
          
          if (failedTestSuites.size() < 10) {
            def commaSeparatedListOfLabels = ""
            featureLabelMap.each { testSuite, retryLabel ->
              failureMsg += " - Test Suite *${testSuite}* failed :red_circle: (label :label: *${retryLabel}*)\n"
              commaSeparatedListOfLabels += "${retryLabel}"
              // add comma except for the last one
              if(testSuite != featureLabelMap.keySet().last()) {
                commaSeparatedListOfLabels += ","                
              }
            }
            failureMsg += " To label & retry, just send the following message: \n @qa-bot replay-pr ${REPO_NAME} ${PR_NUMBER} ${commaSeparatedListOfLabels}"
          } else {
            failureMsg += " >10 test suites failed on this PR check :rotating_light:. This might indicate an environmental/config issue. cc: @planxqa :allthethings: :allthethings: :allthethings:"
          }
          failureMsg += "\n " + commonMsg

          slackSend(color: 'bad', channel: "#gen3-qa-notifications", message: failureMsg)
          currentBuild.result = 'ABORTED'
          error("aborting build - testsuite failed")
        } else {
          successMsg += "\n " + commonMsg
          slackSend(color: "#439FE0", channel: "#gen3-qa-notifications", message: successMsg)
        }
      })
    }
  }
}

/**
* Awesomely runs gen3-qa integration tests IN PARALLEL ヽ(ಠ_ಠ)ノ
*
* @param namespace - namespace to run integration tests in
* @param service - name of service the test is being run for
* @param testedEnv - environment the test is being run for (for manifest PRs)
*/
def runIntegrationTests(String namespace, String service, String testedEnv, String isGen3Release,  String selectedTest) {
  withCredentials([
    usernamePassword(credentialsId: 'ras-test-user1-for-ci-tests', usernameVariable: 'RAS_TEST_USER_1_USERNAME', passwordVariable: 'RAS_TEST_USER_1_PASSWORD'),
    usernamePassword(credentialsId: 'ras-test-user2-for-ci-tests', usernameVariable: 'RAS_TEST_USER_2_USERNAME', passwordVariable: 'RAS_TEST_USER_2_PASSWORD')
  ]) {
    dir('gen3-qa') {
      gen3Qa(namespace, {
        sh "mkdir -p output"

        // Need a mutex so only one parallel test can execute the codeceptjs bootstrap script
        // We must run the gcp setup from https://github.com/uc-cdis/gen3-qa/blob/master/test_setup.js only once
        // otherwise we will face "There were concurrent policy changes" errors
        // The first thread to reach this stage must drop a marker file
        if (fileExists('gen3-qa-mutext.marker')) {
          echo 'gen3-qa-mutext.marker found!'
          // TODO: Initiate polling and only resume other parallel tests once
          // the program project and all GCP dependencies are properly set
          // we need to look into a callback from gen3-qa/test_setup.js
          sleep(20)
          sh(script: """
            #!/bin/bash +x
            # disable bootstrap script from codeceptjs
            sed -i '/bootstrap:/d' codecept.conf.js
          """, returntdout: true);
        } else {
          echo 'the marker file has not been created yet, creating gen3-qa-mutext.marker now...'
          writeFile(file: 'gen3-qa-mutext.marker', text: "--> ${selectedTest} got here first!")
          // Give a chance for the first thread to create program and project
        }
             
        testResult = null
        List<String> failedTestSuites = [];
        testResult = sh(script: """
          bash ./run-tests.sh ${namespace} --service=${service} --testedEnv=${testedEnv} --isGen3Release=${isGen3Release} --selectedTest=${selectedTest}
        """, returnStatus: true);
        
        dir('output') {
          // collect and archive service logs
          echo "Archiving service logs via 'gen3 logs snapshot'"
          sh(script: "bash ${env.WORKSPACE}/cloud-automation/gen3/bin/logs.sh snapshot", returnStatus: true)
        }

        if (testResult != 0) {
          // Mark as unstable for proper visual feedback in blue ocean
          // but let the ProcessCIResults stage deal with the error handling
          currentBuild.result = 'UNSTABLE' 
          error("testsuite ${selectedTest} failed")
        }
      })
    }
  }
}

/**
* Process the results from the parallel testing
*
* @param failedTestSuites - list of test suites that failed during parallel execution
*/
def processCIResults(String namespace, List<String> failedTestSuites = []) {
  dir('gen3-qa') {
    gen3Qa(namespace, {
      def successMsg = "Successful CI run for https://github.com/uc-cdis/$REPO_NAME/pull/$PR_NUMBER :tada:"
      def commonMsg = "Duration ${currentBuild.durationString} :clock1:\n"
      if (failedTestSuites.size() > 0) {
        def failureMsg = "CI Failure on https://github.com/uc-cdis/$REPO_NAME/pull/$PR_NUMBER :facepalm: \n"
        failureMsg += failedTestSuites.collect { " - *${it}* failed :red_circle:" }.join "\n"         
        commaSeparatedListOfLabels = failedTestSuites.join ","

        failureMsg += " To label :label: & retry :jenkins:, just send the following message: \n @qa-bot replay-pr ${REPO_NAME} ${PR_NUMBER} ${commaSeparatedListOfLabels}"
      
        failureMsg += "\n " + commonMsg

        slackSend(color: 'bad', channel: "#gen3-qa-notifications", message: failureMsg)
        currentBuild.result = 'ABORTED'
        error("aborting build - testsuite failed")
      } else {
        successMsg += "\n " + commonMsg
        slackSend(color: "#439FE0", channel: "#gen3-qa-notifications", message: successMsg)
      }
    })
  }
}

/**
* Simulates data used in tests
*
* @param namespace - namespace to simulate data for
*/
def simulateData(String namespace, String testedEnv="") {
  dir('gen3-qa') {
    gen3Qa(namespace, {
      sh "bash ./jenkins-simulate-data.sh ${namespace} ${testedEnv}"
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
    sh "unzip ${download_location}"    

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
*
* @param namespace - namespace to identify the name of the s3 bucket
*/
def cleanS3(namespace) {
  gen3Qa(namespace, {
    qaBucket = "${KUBECTL_NAMESPACE}-databucket-gen3"
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
  })
}

/**
* Delete Service Accounts from the dcf-integration Google Cloud Platform project
* This should avoid intermittent failures in the CI tests
*/
def deleteGCPServiceAccounts(jenkinsNamespace) {
  withCredentials([file(credentialsId: 'fence-google-app-creds-secret', variable: 'MY_SECRET_GCLOUD_APP_CREDENTIALS_FILE')]) {
    sh '''
      mv $MY_SECRET_GCLOUD_APP_CREDENTIALS_FILE fence_google_app_creds_secret.json
      gcloud auth activate-service-account --key-file fence_google_app_creds_secret.json
    '''
    def SELECTED_JENKINS_NAMESPACE = jenkinsNamespace;
    def JPREFIX="";

    switch(SELECTED_JENKINS_NAMESPACE) {
    case "jenkins-dcp":
      println("deleting jdcp svc accounts");
      JPREFIX="jdcp"
      break;
    case "jenkins-brain":
      println("deleting jbrain svc accounts");
      JPREFIX="jbrain"
      break;
    case "jenkins-blood":
      println("deleting jblood svc accounts");
      JPREFIX="jblood"
      break;
    case "jenkins-genomel":
      println("deleting jgmel svc accounts");
      JPREFIX="jgmel"
      break;
    case "jenkins-niaid":
      println("deleting jniaid svc accounts");
      JPREFIX="jniaid"
      break;
    case "ci-env-1":
      println("deleting cienv1 svc accounts");
      JPREFIX="cienv1"
      break;
    default:
      println("invalid jenkins namespace: " + SELECTED_JENKINS_NAMESPACE);
      // If the CI environment is not listed here
      // it is probably not configured for google integration tests
      return 0;
    }

    println("finding all the service accounts associated with this Jenkins namespace...");
    def set_project = sh(script: "gcloud config set project dcf-integration || exit 0", returnStdout: true);
    println("set_project: ${set_project}");

    def sas = sh(script: "gcloud iam service-accounts list --filter=\"Email:($JPREFIX)\" --format=\"table(Email)\" || exit 0", returnStdout: true);

    def svc_accounts = sas.split("\n");
    for (int i = 0; i < svc_accounts.length; i++) {
      // Skip header
      if (i == 0) continue
      def sa = svc_accounts[i];
      println("deleting svc account: " + sa);

      def sa_deletion_result = sh(script: "gcloud iam service-accounts delete $sa  --quiet || exit 0", returnStdout: true);
      println "sa_deletion_result: ${sa_deletion_result}";
    }
    println("The Service Accounts correspondent to this jenkins namespace have been deleted.");
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
