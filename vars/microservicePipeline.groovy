#!groovy

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

/**
* Pipline for building and testing microservices
* 
* @param config - pipeline configuration
*/
def call(Map config) {
  node {
    def AVAILABLE_NAMESPACES = ['jenkins-blood', 'jenkins-brain', 'jenkins-niaid', 'jenkins-dcp', 'jenkins-genomel']
    List<String> namespaces = []
    isDocumentationOnly = false
    isGen3Release = "false"
    selectedTest = ""
    prLabels = null
    kubectlNamespace = null
    kubeLocks = []
    testedEnv = "" // for manifest pipeline
    pipeConfig = pipelineHelper.setupConfig(config)
    pipelineHelper.cancelPreviousRunningBuilds()
    prLabels = githubHelper.fetchLabels()
    try {
      stage('CleanWorkspace') {
        cleanWs()
      }
      stage('FetchCode') {
        gitHelper.fetchAllRepos(pipeConfig['currentRepoName'])
      }
      stage('CheckPRLabels') {
        for(label in prLabels) {
          println(label['name']);
          switch(label['name']) {
            case ~/^test-.*/:
              println('Select a specific test suite and feature')
              selectedTestLabel = label['name'].split("-")
              println "selected test: suites/" + selectedTestLabel[1] + "/" + selectedTestLabel[2] + ".js"
              selectedTest = "suites/" + selectedTestLabel[1] + "/" + selectedTestLabel[2] + ".js"
              break
            case "doc-only":
              println('Skip tests if git diff matches expected criteria')
	      isDocumentationOnly = docOnlyHelper.checkTestSkippingCriteria()
              break
            case "gen3-release":
              println('Enable additional tests and automation')
              isGen3Release = "true"
              break
            case "debug":
              println("Call npm test with --debug")
              println("leverage CodecepJS feature require('codeceptjs').output.debug feature")
              break
            case AVAILABLE_NAMESPACES:
              println('found this namespace label! ' + label['name']);
              namespaces.add(label['name'])
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
      }      
      if (pipeConfig.MANIFEST == null || pipeConfig.MANIFEST == false || pipeConfig.MANIFEST != "True") {
        // Setup stages for NON manifest builds
        stage('WaitForQuayBuild') {
          if(!isDocumentationOnly) {
            quayHelper.waitForBuild(
              pipeConfig['quayRegistry'],
              pipeConfig['currentBranchFormatted']
            )
	  } else {
	    Utils.markStageSkippedForConditional(STAGE_NAME)
          }
        }
        stage('SelectNamespace') {
          if(!isDocumentationOnly) {
            (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(pipeConfig['UID'], namespaces)
            kubeLocks << lock
	  } else {
	    Utils.markStageSkippedForConditional(STAGE_NAME)
          }
        }
        stage('ModifyManifest') {
          if(!isDocumentationOnly) {
            manifestHelper.editService(
              kubeHelper.getHostname(kubectlNamespace),
              pipeConfig.serviceTesting.name,
              pipeConfig.serviceTesting.branch
            )
	  } else {
	    Utils.markStageSkippedForConditional(STAGE_NAME)
          }
        }
      }

      if (pipeConfig.MANIFEST != null && (pipeConfig.MANIFEST == true || pipeConfig.MANIFEST == "True")) {
        // Setup stages for MANIFEST builds
        stage('SelectNamespace') {
          if(!isDocumentationOnly) {
            (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(pipeConfig['UID'], namespaces)
            kubeLocks << lock
          } else {
            Utils.markStageSkippedForConditional(STAGE_NAME)
          }
        }
        stage('ModifyManifest') {
          if(!isDocumentationOnly) {
            testedEnv = manifestHelper.manifestDiff(kubectlNamespace)
	  } else {
	    Utils.markStageSkippedForConditional(STAGE_NAME)
          }
	}
      }

      stage('K8sReset') {
        if(!isDocumentationOnly) {
          // adding the reset-lock lock in case reset fails before unlocking
          kubeLocks << kubeHelper.newKubeLock(kubectlNamespace, "gen3-reset", "reset-lock")
          kubeHelper.reset(kubectlNamespace)
        } else {
          Utils.markStageSkippedForConditional(STAGE_NAME)
        }
      }
      stage('VerifyClusterHealth') {
        if(!isDocumentationOnly) {
          kubeHelper.waitForPods(kubectlNamespace)
          testHelper.checkPodHealth(kubectlNamespace)
        } else {
          Utils.markStageSkippedForConditional(STAGE_NAME)
        }
      }
      stage('GenerateData') {
        if(!isDocumentationOnly) {
          testHelper.simulateData(kubectlNamespace)
        } else {
          Utils.markStageSkippedForConditional(STAGE_NAME)
        }
      }
      stage('FetchDataClient') {
        if(!isDocumentationOnly) {
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
      }
      stage('RunTests') {
        if(!isDocumentationOnly) {
          testHelper.runIntegrationTests(
            kubectlNamespace,
            pipeConfig.serviceTesting.name,
            testedEnv,
            isGen3Release,
            selectedTest
          )
        } else {
          Utils.markStageSkippedForConditional(STAGE_NAME)
        }
      }
      stage('CleanS3') {
        if(!isDocumentationOnly) {
          testHelper.cleanS3()
	} else {
	  Utils.markStageSkippedForConditional(STAGE_NAME)
        }
      }
    }
    catch (e) {
      pipelineHelper.handleError(e)
    }
    finally {
      stage('Post') {
        kubeHelper.teardown(kubeLocks)
        testHelper.teardown()
        pipelineHelper.teardown(currentBuild.result)
      }
    }
  }
}
