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
    List<String> selectedTags = []
    doNotRunTests = false
    runParallelTests = false
    skipQuayBuild = false
    skipK8sCleanUp = false
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
  initContainers:
  - name: wait-for-jenkins-connection
    image: quay.io/cdis/gen3-ci-worker:master
    command: ["/bin/sh","-c"]
    args: ["while [ $(curl -sw '%{http_code}' http://jenkins-master-service:8080/tcpSlaveAgentListener/ -o /dev/null) -ne 200 ]; do sleep 5; echo 'Waiting for jenkins connection ...'; done"]
  containers:
  - name: jnlp
    command: ["/bin/sh","-c"]
    args: ["sleep 30; /usr/local/bin/jenkins-agent"]
    resources:
      requests:
        cpu: 500m
        memory: 500Mi
        ephemeral-storage: 500Mi
  - name: selenium
    image: 707767160287.dkr.ecr.us-east-1.amazonaws.com/gen3/selenium-standalone-chrome:112.0
    imagePullPolicy: Always
    ports:
    - containerPort: 4444
    readinessProbe:
      httpGet:
        path: /status
        port: 4444
      timeoutSeconds: 60
    resources:
      requests:
        cpu: 500m
        memory: 500Mi
        ephemeral-storage: 500Mi
  - name: shell
    image: quay.io/cdis/gen3-ci-worker:master
    imagePullPolicy: Always
    command:
    - sleep
    args:
    - infinity
    resources:
      requests:
        cpu: 500m
        memory: 500Mi
        ephemeral-storage: 1Gi
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
                                    case ~/^@.*/:
                                        println('Selecting a specific test tag')
                                        println "selected tag: " + label['name']
                                        selectedTags.add(label['name'])
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
                                    case "skip-k8s-cleanup":
                                        skipK8sCleanUp = true
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
                            // If a specific test suite or tag is not specified, run them all
                            if (selectedTests.size == 0 && selectedTags.size == 0) {
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
                                        selectedTags,
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
                    }
                    testHelper.teardown(doNotRunTests)
                    pipelineHelper.teardown(currentBuild.result)
                }
            }
        }
    }
}
