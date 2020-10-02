#!groovy

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

/**
* Pipline for building and testing microservices
* 
* @param config - pipeline configuration
*/
def call(Map config) {
  node('master') {
    def AVAILABLE_NAMESPACES = ['jenkins-blood', 'jenkins-brain', 'jenkins-niaid', 'jenkins-dcp', 'jenkins-genomel']
    List<String> namespaces = []
    List<String> selectedTests = []
    doNotRunTests = false
    isGen3Release = "false"
    prLabels = null
    newWS = ""
    kubectlNamespace = null
    kubeLocks = []
    testedEnv = "" // for manifest pipeline
    pipeConfig = pipelineHelper.setupConfig(config)
    pipelineHelper.cancelPreviousRunningBuilds()
    prLabels = githubHelper.fetchLabels()

    try {
      stage('CleanWorkspace') {
        // Set up new workspace
        newWS = env.WORKSPACE.replaceAll(" ", "_");
        cleanWs()
      }
      stage('FetchCode') {
        gitHelper.fetchAllRepos(pipeConfig['currentRepoName'])
      }
      stage('OrganizeWorkspace') {
        // Set up new workspace
        PR_NUMBER = env.BRANCH_NAME;
        REPO_NAME = env.JOB_NAME.split('/')[1];
        newWs = env.JENKINS_HOME/workspace/CDIS_GitHub_Org/${REPO_NAME}/${PR_NUMBER}
        sh(script: "mkdir -p ${newWs} && mv * ../${newWS}");
        env.WORKSPACE = newWS
        sh(script: "ls -ilha");
      }
      stage('CheckPRLabels') {
        // giving a chance for auto-label gh actions to catch up
        sleep(10)
        for(label in prLabels) {
          println(label['name']);
          switch(label['name']) {
            case ~/^test-.*/:
              println('Select a specific test suite and feature')
              selectedTestLabel = label['name'].split("-")
              println "selected test: suites/" + selectedTestLabel[1] + "/" + selectedTestLabel[2] + ".js"
              selectedTest = "suites/" + selectedTestLabel[1] + "/" + selectedTestLabel[2] + ".js"
              selectedTests.add(selectedTest)
              break
            case "doc-only":
              println('Skip tests if git diff matches expected criteria')
	      doNotRunTests = docOnlyHelper.checkTestSkippingCriteria()
              break
            case "decommission-environment":
              println('Skip tests if an environment folder is deleted')
              doNotRunTests = decommissionEnvHelper.checkDecommissioningEnvironment()
            case "gen3-release":
              println('Enable additional tests and automation')
              isGen3Release = "true"
              break
            case "debug":
              println("Call npm test with --debug")
              println("leverage CodecepJS feature require('codeceptjs').output.debug feature")
              break
            case "not-ready-for-ci":
              currentBuild.result = 'ABORTED'
              error('This PR is not ready for CI yet, aborting...')
              break
            case AVAILABLE_NAMESPACES:
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
        }
      }      
      if (pipeConfig.MANIFEST == null || pipeConfig.MANIFEST == false || pipeConfig.MANIFEST != "True") {
        // Setup stages for NON manifest builds
        stage('WaitForQuayBuild') {
          if(!doNotRunTests) {
            quayHelper.waitForBuild(
              pipeConfig['quayRegistry'],
              pipeConfig['currentBranchFormatted']
            )
	  } else {
	    Utils.markStageSkippedForConditional(STAGE_NAME)
          }
        }
        stage('SelectNamespace') {
          if(!doNotRunTests) {
            (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(pipeConfig['UID'], namespaces)
            kubeLocks << lock
	  } else {
	    Utils.markStageSkippedForConditional(STAGE_NAME)
          }
        }
        stage('CleanUp3rdPartyResources') {
          if(!doNotRunTests) {
            testHelper.deleteGCPServiceAccounts(kubectlNamespace)
          } else {
            Utils.markStageSkippedForConditional(STAGE_NAME)
          }
        }
        stage('ModifyManifest') {
          if(!doNotRunTests) {
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
          if(!doNotRunTests) {
            (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(pipeConfig['UID'], namespaces)
            kubeLocks << lock
          } else {
            Utils.markStageSkippedForConditional(STAGE_NAME)
          }
        }
        stage('CleanUp3rdPartyResources') {
          if(!doNotRunTests) {
            testHelper.deleteGCPServiceAccounts(kubectlNamespace)
          } else {
            Utils.markStageSkippedForConditional(STAGE_NAME)
          }
        }
        stage('ModifyManifest') {
          if(!doNotRunTests) {
            testedEnv = manifestHelper.manifestDiff(kubectlNamespace)
	  } else {
	    Utils.markStageSkippedForConditional(STAGE_NAME)
          }
	}
      }

      stage('K8sReset') {
        if(!doNotRunTests) {
          // adding the reset-lock lock in case reset fails before unlocking
          kubeLocks << kubeHelper.newKubeLock(kubectlNamespace, "gen3-reset", "reset-lock")
          kubeHelper.reset(kubectlNamespace)
        } else {
          Utils.markStageSkippedForConditional(STAGE_NAME)
        }
      }
      stage('VerifyClusterHealth') {
        if(!doNotRunTests) {
          kubeHelper.waitForPods(kubectlNamespace)
          testHelper.checkPodHealth(kubectlNamespace, testedEnv)
        } else {
          Utils.markStageSkippedForConditional(STAGE_NAME)
        }
      }
      stage('GenerateData') {
        if(!doNotRunTests) {
          testHelper.simulateData(kubectlNamespace)
        } else {
          Utils.markStageSkippedForConditional(STAGE_NAME)
        }
      }
      stage('FetchDataClient') {
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
      }
      stage('RunTests') {
        if(!doNotRunTests) {
          testHelper.runIntegrationTests(
            kubectlNamespace,
            pipeConfig.serviceTesting.name,
            testedEnv,
            isGen3Release,
            selectedTests
          )
        } else {
          Utils.markStageSkippedForConditional(STAGE_NAME)
        }
      }
      stage('CleanS3') {
        if(!doNotRunTests) {
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
