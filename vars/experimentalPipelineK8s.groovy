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
    isGen3Release = "false"
    isNightlyBuild = "false"
    kubectlNamespace = null
    kubeLocks = []
    testedEnv = "" // for manifest pipeline
    regexMatchRepoOwner = "" // to track the owner of the github repository

    def AVAILABLE_NAMESPACES = ciEnvsHelper.fetchCIEnvs()
    pipelineHelper.cancelPreviousRunningBuilds()

    pipeConfig = pipelineHelper.setupConfig(config)

    pipeline {
        agent {
            kubernetes {
                yaml '''
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: ephemeral-ci-run
    netnolimit: "yes"
spec:
  containers:
  - name: shell
    image: quay.io/cdis/gen3-ci-worker:master
    imagePullPolicy: Always
    command:
    - sleep
    args:
    - infinity
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
                                println(label['name']);
                                switch(label['name']) {
                                    case ~/^test-.*/:
                                        println('Select a specific test suite and feature')
                                        selectedTestLabel = label['name'].split("-")
                                        println "selected test: suites/" + selectedTestLabel[1] + "/" + selectedTestLabel[2] + ".js"
                                        selectedTest = "suites/" + selectedTestLabel[1] + "/" + selectedTestLabel[2] + ".js"
                                        selectedTests.add(selectedTest)
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
                                        println("Call npm test with --debug")
                                        println("leverage CodecepJS feature require('codeceptjs').output.debug feature")
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
                                    selectedTests.add("suites/portal/pfbExportTest.js")
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

                                    if(pipeConfig.IMAGES_TO_BUILD != null && pipeConfig.IMAGES_TO_BUILD.size > 0){
                                        println("### ## IMAGES_TO_BUILD: ${pipeConfig.IMAGES_TO_BUILD }")
                                        for (image_to_build in pipeConfig.IMAGES_TO_BUILD) {
                                            quayHelper.waitForBuild(
                                                image_to_build,
                                                currentBranchFormatted,
                                                isOpenSourceContribution
                                            )
                                        }
                                    } else{
                                        quayHelper.waitForBuild(
                                            pipeConfig['quayRegistry'],
                                            currentBranchFormatted,
                                            isOpenSourceContribution
                                        )
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
            stage('ProvisionCIEnv') {
                steps {
                    script {
                        kubeHelper.provisionCIEnv()
                    }
                }
            }
            stage('VerifyClusterHealth') {
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
            stage('RunTests') {
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
                                        selectedTests
                                    )
                                } else {
                                    Utils.markStageSkippedForConditional(STAGE_NAME)
                                }
                            } catch (ex) {
                                metricsHelper.writeMetricWithResult(STAGE_NAME, false)
                                throw ex
                            }
                        }
                    }
                }
            }
            stage('CleanS3') {
                steps {
                    script {
                        try {
	                    if(!doNotRunTests) {
                                testHelper.cleanS3(kubectlNamespace)
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
        }
        post {
            always {
                script {
                    kubeHelper.teardown(kubeLocks)
                    testHelper.teardown(doNotRunTests)
                    pipelineHelper.teardown(currentBuild.result)
	        }
            }
        }
    }
}
