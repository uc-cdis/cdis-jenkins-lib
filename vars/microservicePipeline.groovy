#!groovy

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

/**
* Pipline for building and testing microservices
*
* @param config - pipeline configuration
*/
def call(Map config) {
    List<String> namespaces = []
    List<String> selectedTests = []
    doNotRunTests = false
    runParallelTests = false
    skipQuayBuild = false
    debug = "false"
    isGen3Release = "false"
    isNightlyBuild = "false"
    kubectlNamespace = null
    kubeLocks = []
    testedEnv = "" // for manifest pipeline
    regexMatchRepoOwner = "" // to track the owner of the github repository

    pipelineHelper.cancelPreviousRunningBuilds()

    pipeConfig = pipelineHelper.setupConfig(config)

    println("----------------------- PIPECONFIG ---------------------")
    println(pipeConfig)
    println("--------------------------------------------------------")

    def AVAILABLE_NAMESPACES
    if (pipeConfig.MANIFEST == "True") {
        AVAILABLE_NAMESPACES = ciEnvsHelper.fetchCIEnvs("release")
    } else {
        AVAILABLE_NAMESPACES = ciEnvsHelper.fetchCIEnvs("service")
    }

    pipeline {
        agent {
            kubernetes {
                yaml '''
apiVersion: v1
kind: Pod
metadata:
  annotations:
    karpenter.sh/do-not-evict: true
  labels:
    app: ephemeral-ci-run
    netnolimit: "yes"
spec:
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
        - matchExpressions:
          - key: eks.amazonaws.com/capacityType
            operator: In
            values:
            - ONDEMAND
        - matchExpressions:
          - key: karpenter.sh/capacity-type
            operator: In
            values:
            - on-demand
  containers:
  - name: jnlp
    command: ["/bin/sh","-c"]
    args: ["sleep 30; /usr/local/bin/jenkins-agent"]
    resources:
      requests:
        cpu: 500m
        memory: 500Mi
        ephemeral-storage: 500Mi
    livenessProbe:
      exec:
        command:
	- curl
        - http://jenkins-master-service:8080/tcpSlaveAgentListener/
      failureThreshold: 3
      periodSeconds: 10
    startupProbe:
      exec:
        command:
	- curl
        - http://jenkins-master-service:8080/tcpSlaveAgentListener/
      failureThreshold: 30
      periodSeconds: 10
  - name: shell
    image: quay.io/cdis/gen3-ci-worker:master
    imagePullPolicy: Always
    command:
    - sleep
    args:
    - infinity
    resources:
      requests:
        cpu: 1
        memory: 2Gi
        ephemeral-storage: 2Gi
    env:
    - name: AWS_DEFAULT_REGION
      value: us-east-1
    - name: JAVA_OPTS
      value: "-Xmx3072m"
    - name: AWS_ACCESS_KEY_ID
      valueFrom:
        secretKeyRef:
          name: jenkins-secret
          key: aws_access_key_id
    - name: AWS_SECRET_ACCESS_KEY
      valueFrom:
        secretKeyRef:
          name: jenkins-secret
          key: aws_secret_access_key
    - name: GOOGLE_APP_CREDS_JSON
      valueFrom:
        secretKeyRef:
          name: jenkins-g3auto
          key: google_app_creds.json
  serviceAccount: jenkins-service
  serviceAccountName: jenkins-service
'''
        defaultContainer 'shell'
            }
        }
        stages {
            stage('CleanWorkspace') {
                steps {
                script {
                        try {
                        cleanWs()
                    } catch (e) {
                        pipelineHelper.handleError(e)
                    }
                }
            }
            }
            stage('FetchCode') {
            steps {
                script {
                    try {
                        gitHelper.fetchAllRepos(pipeConfig['currentRepoName'])
                    } catch (e) {
                        pipelineHelper.handleError(e)
                    }
                }
            }
            }
            stage('CheckPRLabels') {
                steps {
                    script {
                        try {
                            // if the changes are doc-only, automatically skip the tests
                            doNotRunTests = doNotRunTests || docOnlyHelper.checkTestSkippingCriteria()

                            // prLabels are added to the config map in vars/testPipeline.groovy
                            for(label in config.prLabels) {
                                println("Found label: ${label['name']}");
                                switch(label['name']) {
                                    case ~/^test-.*/:
                                        println('Select a specific test suite and feature')
                                        selectedTestLabel = label['name'].split("-")
                                        println "selected test: suites/" + selectedTestLabel[1] + "/" + selectedTestLabel[2] + ".js"
                                        selectedTest = "suites/" + selectedTestLabel[1] + "/" + selectedTestLabel[2] + ".js"
                                        selectedTests.add(selectedTest)
                                        debug = "true"
                                        break
                                    case "parallel-testing":
                                        println('Run labelled test suites in parallel')
                                        runParallelTests = true
                                        break
                                    case "decommission-environment":
                                        println('Skip tests if an environment folder is deleted')
                                        doNotRunTests = doNotRunTests || decommissionEnvHelper.checkDecommissioningEnvironment()
                                    case "gen3-release":
                                        println('Enable additional tests and automation')
                                        isGen3Release = "true"
                                        break
                                    case "nightly-run":
                                        println('Enable additional tests and automation for our nightly-release')
                                        // Treat nightly build as a gen3-release labelled PR
                                        isGen3Release = "true"
                                        isNightlyBuild = "true"
                                        break
                                    case "debug":
                                        // Run tests in debug mode
                                        debug = "true"
                                        break
                                    case "not-ready-for-ci":
                                        currentBuild.result = 'ABORTED'
                                        error('This PR is not ready for CI yet, aborting...')
                                        break
                                    case ~/^jenkins-.*/:
                                        println('found this namespace label! ' + label['name']);
                                        namespaces.add(label['name'])
                                        break
                                    case "qaplanetv2":
                                        println('This PR check will run in a qaplanetv2 environment! ');
                                        namespaces.add('ci-env-1')
                                        break
                                    case "skip-quay-build":
                                        skipQuayBuild = true
                                        break
                                    default:
                                        println('no-effect label')
                                        break
                                }
                            }
                            // If none of the jenkins envs. have been selected pick one at random
                            if (namespaces.size == 0) {
                                namespaces = AVAILABLE_NAMESPACES
                            }
                            // If a specific test suite is not specified, run them all
                            if (selectedTests.size == 0) {
                                selectedTests.add("all")

                                // include long running tests.in the nightly-build
                                if (isNightlyBuild == "true") {
                                    exportToPFBFeatureEnabledExitCode = sh(
                                        returnStatus: true,
                                        script: "grep '\"export-to-pfb\"' tmpGitClone/nightly.planx-pla.net/portal/gitops.json"
                                    ) as Integer
                                    if (exportToPFBFeatureEnabledExitCode == 0) {
                                        selectedTests.add("suites/portal/pfbExportTest.js")
                                        println('selected PFB Export tests since PFB Export feature is enabled for environment')
                                    }
                                }
                            }
                        } catch (ex) {
                            metricsHelper.writeMetricWithResult(STAGE_NAME, false)
                            throw ex
                        }
                        metricsHelper.writeMetricWithResult(STAGE_NAME, true)
                    }
                }
            }
            stage('WaitForQuayBuild') {
                options {
                    timeout(time: 30, unit: 'MINUTES')   // timeout on this stage
                }
                steps {
                script {
                    try {
                            if(!doNotRunTests) {
                                if (pipeConfig.MANIFEST == null || pipeConfig.MANIFEST == false || pipeConfig.MANIFEST != "True") {
                                      // for NON manifest builds
                                    def REPO_NAME = env.JOB_NAME.split('/')[1]
                                    def repoFromPR = githubHelper.fetchRepoURL()
                                    regexMatchRepoOwner = (repoFromPR =~ /.*api.github.com\/repos\/(.*)\/${REPO_NAME}/)[0];
                                    println("### ## regexMatchRepoOwner: ${regexMatchRepoOwner}")

                                    // handle feature branch image builds from forked repos
                                    def isOpenSourceContribution = regexMatchRepoOwner[1] != "uc-cdis"
                                    def currentBranchFormatted = isOpenSourceContribution ? "automatedCopy-${pipeConfig['currentBranchFormatted']}" : pipeConfig['currentBranchFormatted'];
                                    println("### ## currentBranchFormatted: ${currentBranchFormatted}")

                                    if(!skipQuayBuild) {
                                        if(pipeConfig.IMAGES_TO_BUILD != null && pipeConfig.IMAGES_TO_BUILD.size > 0){
                                            println("### ## IMAGES_TO_BUILD: ${pipeConfig.IMAGES_TO_BUILD }")
                                            for (image_to_build in pipeConfig.IMAGES_TO_BUILD) {
                                                quayHelper.waitForBuild(
                                                    image_to_build,
                                                    currentBranchFormatted
                                                )
                                            }
                                        } else{
                                            quayHelper.waitForBuild(
                                                pipeConfig['quayRegistry'],
                                                currentBranchFormatted
                                            )
                                        }
                                    }
                                  } else {
                                    Utils.markStageSkippedForConditional(STAGE_NAME)
                                }
                            } else {
                                Utils.markStageSkippedForConditional(STAGE_NAME)
                            }
                        } catch (e) {
                            metricsHelper.writeMetricWithResult(STAGE_NAME, false)
                            pipelineHelper.handleError(e)
                        }
                        metricsHelper.writeMetricWithResult(STAGE_NAME, true)
                    }
                }
            }
            stage('SelectNamespace') {
                steps {
                script {
                    try {
                        if(!doNotRunTests) {
                            println("Picking a namespace from this pool: ${namespaces}");
                            (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(pipeConfig['UID'], namespaces)
                                kubeLocks << lock
                        } else {
                            Utils.markStageSkippedForConditional(STAGE_NAME)
                        }
                    } catch (e) {
                            metricsHelper.writeMetricWithResult(STAGE_NAME, false)
                        pipelineHelper.handleError(e)
                    }
                        currentBuild.displayName = "#${BUILD_NUMBER} - ${kubectlNamespace}"
                        metricsHelper.writeMetricWithResult(STAGE_NAME, true)
                }
                }
            }
            stage('CleanUp3rdPartyResources') {
                steps {
                    script {
                        try {
                            if(!doNotRunTests) {
                                testHelper.deleteGCPServiceAccounts(kubectlNamespace)
                            } else {
                                Utils.markStageSkippedForConditional(STAGE_NAME)
                            }
                        } catch (ex) {
                            metricsHelper.writeMetricWithResult(STAGE_NAME, false)
                            throw ex
                        }
                        metricsHelper.writeMetricWithResult(STAGE_NAME, true)
                    }
                }
            }
            stage('ModifyManifest') {
                options {
                    timeout(time: 2, unit: 'MINUTES')   // timeout on this stage
                }
                steps {
                    script {
                        try {
                            if(!doNotRunTests) {
                                // TODO: We can only left-shift Dictionary changes once we achieve ephemeral CI envs.
                                if (pipeConfig.DICTIONARY != null && (pipeConfig.DICTIONARY == true || pipeConfig.DICTIONARY == "True")) {
                                    manifestHelper.setDictionary(
                                        kubeHelper.getHostname(kubectlNamespace)
                                    )
                                } else if  (pipeConfig.MANIFEST == null || pipeConfig.MANIFEST == false || pipeConfig.MANIFEST != "True") {
                                    def quayBranchName = regexMatchRepoOwner[1] == "uc-cdis" ? pipeConfig.serviceTesting.branch : "automatedCopy-${pipeConfig.serviceTesting.branch}";
                                    println("### ## quayBranchName: ${quayBranchName}")
                                    if (pipeConfig.IMAGES_TO_BUILD != null && pipeConfig.IMAGES_TO_BUILD.size > 0) {
                                        for (image_to_build in pipeConfig.IMAGES_TO_BUILD) {
                                            manifestHelper.editService(
                                                kubeHelper.getHostname(kubectlNamespace),
                                                image_to_build,
                                                quayBranchName
                                            )

                                        }
                                    } else {
                                        manifestHelper.editService(
                                            kubeHelper.getHostname(kubectlNamespace),
                                            pipeConfig.serviceTesting.name,
                                            quayBranchName
                                        )

                                    }
                                    // If this is a service repo, just figure out the jenkins env hostname
                                    testedEnv = kubeHelper.getHostname(kubectlNamespace)
                                } else {
                                    // If this is a manifest repo, identify the env folder based on the git diff
                                    // this function also calls overwriteConfigFolders
                                    testedEnv = manifestHelper.manifestDiff(kubectlNamespace)

                                    // the nightly build flow will always set testedEnv to nightly.planx-pla.net
                                    // unless we fetch the correct name of the mutated environment
                                    if (isNightlyBuild == "true") {
                                        testedEnv = manifestHelper.fetchHostnameFromMutatedEnvironment()
                                    }
                                }
                            } else {
                                Utils.markStageSkippedForConditional(STAGE_NAME)
                            }
                        } catch (ex) {
                            metricsHelper.writeMetricWithResult(STAGE_NAME, false)
                            throw ex
                        }
                        metricsHelper.writeMetricWithResult(STAGE_NAME, true)
                    }
                }
            }
            stage('K8sReset') {
                options {
                    timeout(time: 90, unit: 'MINUTES')   // timeout on this stage
                }
                steps {
                    script {
                        try {
                            if(!doNotRunTests) {
                                // adding the reset-lock lock in case reset fails before unlocking
                                kubeLocks << kubeHelper.newKubeLock(kubectlNamespace, "gen3-reset", "reset-lock")
                                kubeHelper.reset(kubectlNamespace)
                            } else {
                                Utils.markStageSkippedForConditional(STAGE_NAME)
                            }
                        } catch (ex) {
                            // ignore aborted pipelines (not a failure, just some subsequent commit that initiated a new build)
                            if (ex.getClass().getCanonicalName() != "hudson.AbortException" &&
                            ex.getClass().getCanonicalName() != "org.jenkinsci.plugins.workflow.steps.FlowInterruptedException") {
                                metricsHelper.writeMetricWithResult(STAGE_NAME, false)
                                kubeHelper.sendSlackNotification(kubectlNamespace, isNightlyBuild)
                                kubeHelper.saveLogs(kubectlNamespace)
                            }
                            throw ex
                        }
                        metricsHelper.writeMetricWithResult(STAGE_NAME, true)
                    }
                }
            }
            stage('VerifyClusterHealth') {
		options {
                    timeout(time: 30, unit: 'MINUTES')   // timeout on this stage
                }
                steps {
                script {
                        try {
                            if(!doNotRunTests) {
                                kubeHelper.waitForPods(kubectlNamespace)
                                testHelper.checkPodHealth(kubectlNamespace, testedEnv)
                            } else {
                                Utils.markStageSkippedForConditional(STAGE_NAME)
                            }
                        } catch (ex) {
                            metricsHelper.writeMetricWithResult(STAGE_NAME, false)
                            throw ex
                        }
                        metricsHelper.writeMetricWithResult(STAGE_NAME, true)
                    }
                }
            }
            stage('GenerateData') {
                options {
                    timeout(time: 10, unit: 'MINUTES')   // timeout on this stage
                }
                steps {
                script {
                        try {
                            if(!doNotRunTests) {
                                testHelper.simulateData(kubectlNamespace, testedEnv)
                            } else {
                                Utils.markStageSkippedForConditional(STAGE_NAME)
                            }
                        } catch (ex) {
                            metricsHelper.writeMetricWithResult(STAGE_NAME, false)
                            throw ex
                        }
                        metricsHelper.writeMetricWithResult(STAGE_NAME, true)
                    }
                }
            }
            stage('FetchDataClient') {
                steps {
                script {
                        try {
                            if(!doNotRunTests) {
                                // we get the data client from master, unless the service being
                                // tested is the data client itself, in which case we get the
                                // executable for the current branch
                                dataCliBranch = "master"
                                if (pipeConfig.currentRepoName == "cdis-data-client") {
                                    dataCliBranch = env.CHANGE_BRANCH
                                }
                                testHelper.fetchDataClient(dataCliBranch)
                            } else {
                                Utils.markStageSkippedForConditional(STAGE_NAME)
                            }
                        } catch (ex) {
                            metricsHelper.writeMetricWithResult(STAGE_NAME, false)
                            throw ex
                        }
                        metricsHelper.writeMetricWithResult(STAGE_NAME, true)
                    }
                }
            }
            stage('RunTests') {
		options {
                    timeout(time: 3, unit: 'HOURS')   // timeout on this stage
                }
                steps {
                    script {
                        if(!runParallelTests) {
                            try {
                                if(!doNotRunTests) {
                                    testHelper.soonToBeLegacyRunIntegrationTests(
                                        kubectlNamespace,
                                        pipeConfig.serviceTesting.name,
                                        testedEnv,
                                        isGen3Release,
                                        isNightlyBuild,
                                        selectedTests,
                                        debug
                                    )
                                } else {
                                    Utils.markStageSkippedForConditional(STAGE_NAME)
                                }
                            } catch (ex) {
                                metricsHelper.writeMetricWithResult(STAGE_NAME, false)
                                throw ex
                            }
                        } else {

                            /* PARALLELIZED TESTS HERE!!! */

                            // run non-concurrent stuff
                            testHelper.runScriptToCreateProgramsAndProjects(kubectlNamespace)
                            if (selectedTests.contains("all")) {
                                selectedTests = testHelper.gatherAllTestSuiteLabels(kubectlNamespace)
                            }
                            env.GEN3_SKIP_PROJ_SETUP = "true"

                            def testsToParallelize = [:]
                            List<String> failedTestSuites = [];

                            selectedTests.each {selectedTest ->
                                parallelStageName = selectedTest.replace("/", "-")
                                testsToParallelize["parallel-${parallelStageName}"] = {
                                    stage('RunTest') {
                                        steps {
                                            script {
                                                selectedTestLabelSplit = selectedTest.split("/")
                                                selectedTestLabel = "test-" + selectedTestLabelSplit[1] + "-" + selectedTestLabelSplit[2]
                                                println("## ## testedEnv: ${testedEnv}")
                                                try {
                                                    if(!doNotRunTests) {
                                                        println("### ## selectedTestLabel: ${selectedTestLabel}");
                                                        testHelper.runIntegrationTests(
                                                            kubectlNamespace,
                                                            pipeConfig.serviceTesting.name,
                                                            testedEnv,
                                                            selectedTest
                                                        )
                                                    } else {
                                                        Utils.markStageSkippedForConditional(STAGE_NAME)
                                                    }
                                                } catch (ex) {
                                                    println("### ## ex.getMessage(): ${ex.getMessage()}")
                                                    if (ex.getMessage().contains("suites/")) {
                                                        failedTestSuite = ex.getMessage();
                                                        // TODO: Move this logic that translates suites/<suite>/<script>.js
                                                        // into the label formatted string to a helper groovy function somewhere
                                                        failedTestLabelSplit = failedTestSuite.split("/")
                                                        failedTestLabel = "test-" + failedTestLabelSplit[1] + "-" + failedTestLabelSplit[2]
                                                        println("### ## adding to list of failedTestSuites: ${failedTestLabel}");
                                                        failedTestSuites.add(failedTestLabel);
                                                    } else {
                                                        println("## something weird happened. Could not figure out which test failed. Details: ${ex}")
                                                    }
                                                    metricsHelper.writeMetricWithResult(STAGE_NAME, false)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // parallel magic here
                            parallel testsToParallelize

                            stage('ProcessCIResults') {
                                steps {
                                    script {
                                        try {
                                            if(!doNotRunTests) {
                                                testHelper.processCIResults(kubectlNamespace, isNightlyBuild, failedTestSuites)
                                            } else {
                                                Utils.markStageSkippedForConditional(STAGE_NAME)
                                            }
                                        } catch (ex) {
                                            metricsHelper.writeMetricWithResult(STAGE_NAME, false)
                                            throw ex
                                        }
                                        metricsHelper.writeMetricWithResult(STAGE_NAME, true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                script {
                    if(!doNotRunTests) {
                        testHelper.cleanS3(kubectlNamespace)
                        kubeHelper.deleteDeployments(kubectlNamespace)
                    }
                    kubeHelper.teardown(kubeLocks)
                    testHelper.teardown(doNotRunTests)
                    pipelineHelper.teardown(currentBuild.result)
                }
            }
        }
    }
}
