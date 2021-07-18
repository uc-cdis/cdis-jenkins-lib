#!groovy

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

/**
* Pipline for building and testing microservices
*
* @param config - pipeline configuration
*/
def call(Map config) {

  // check if PR contains a label to define where the PR check must run
  // giving a chance for auto-label gh actions to catch up
  sleep(30)
  def prLabels = githubHelper.fetchLabels()
  def pipeConfig = pipelineHelper.setupConfig(config)

  def runOnGen3CIWorker = false;
  if (prLabels.any{label -> label.name == "run-on-jenkins-ci-worker"}) {
    println('Found [run-on-jenkins-ci-worker] label, running CI on ci worker pod...')
    runOnGen3CIWorker = true
  }
  // if this is a Manifests repo, run on separate jenkins worker pod
  // this is overridable by the 'run-on-jenkins-ci-worker' PR label
  if (pipeConfig.MANIFEST == "True") {
    runOnGen3CIWorker = true
  }

  node(runOnGen3CIWorker? 'gen3-ci-worker' : 'master') {
    List<String> namespaces = []
    List<String> selectedTests = []
    doNotRunTests = false
    runParallelTests = false
    isGen3Release = "false"
    isNightlyBuild = "false"
    kubectlNamespace = null
    kubeLocks = []
    testedEnv = "" // for manifest pipeline

    def AVAILABLE_NAMESPACES = ciEnvsHelper.fetchCIEnvs(runOnGen3CIWorker)
    pipelineHelper.cancelPreviousRunningBuilds()

    try {
      stage('CleanWorkspace') {
        cleanWs()
      }
      stage('FetchCode') {
        gitHelper.fetchAllRepos(pipeConfig['currentRepoName'])
      }
      stage('CheckPRLabels') {
       try {
        // if the changes are doc-only, automatically skip the tests
        doNotRunTests = doNotRunTests || docOnlyHelper.checkTestSkippingCriteria()

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

          // include long running tests in the nightly-build
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
      if (pipeConfig.MANIFEST == null || pipeConfig.MANIFEST == false || pipeConfig.MANIFEST != "True") {
       // Setup stages for NON manifest builds
       def REPO_NAME = env.JOB_NAME.split('/')[1]
       def repoFromPR = githubHelper.fetchRepoURL()
       def regexMatchRepoOwner = (repoFromPR =~ /.*api.github.com\/repos\/(.*)\/${REPO_NAME}/)[0];
       println("### ## regexMatchRepoOwner: ${regexMatchRepoOwner}")

       stage('WaitForQuayBuild') {
         try {
          if(!doNotRunTests) {
            def isOpenSourceContribution = regexMatchRepoOwner[1] != "uc-cdis"
            def currentBranchFormatted = isOpenSourceContribution ? "automatedCopy-${pipeConfig['currentBranchFormatted']}" : pipeConfig['currentBranchFormatted'];
            println("### ## currentBranchFormatted: ${currentBranchFormatted}")
            quayHelper.waitForBuild(
              pipeConfig['quayRegistry'],
              currentBranchFormatted,
              isOpenSourceContribution
            )
	  } else {
	    Utils.markStageSkippedForConditional(STAGE_NAME)
          }
         } catch (ex) {
           metricsHelper.writeMetricWithResult(STAGE_NAME, false)
           throw ex
         }
         metricsHelper.writeMetricWithResult(STAGE_NAME, true)
        }
        stage('SelectNamespace') {
         try {
          if(!doNotRunTests) {
            (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(pipeConfig['UID'], namespaces)
            kubeLocks << lock
	  } else {
	    Utils.markStageSkippedForConditional(STAGE_NAME)
          }
         } catch (ex) {
           metricsHelper.writeMetricWithResult(STAGE_NAME, false)
           throw ex
         }
	 currentBuild.displayName = "#${BUILD_NUMBER} - ${kubectlNamespace}"
	 metricsHelper.writeMetricWithResult(STAGE_NAME, true)
        }
        stage('CleanUp3rdPartyResources') {
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
        stage('ModifyManifest') {
         try {
          if(!doNotRunTests) {
           if (pipeConfig.DICTIONARY != null && (pipeConfig.DICTIONARY == true || pipeConfig.DICTIONARY == "True")) {
              manifestHelper.setDictionary(
                kubeHelper.getHostname(kubectlNamespace)
              )
            } else {
              def quayBranchName = regexMatchRepoOwner[1] == "uc-cdis" ? pipeConfig.serviceTesting.branch : "automatedCopy-${pipeConfig.serviceTesting.branch}";
              println("### ## quayBranchName: ${quayBranchName}")
              manifestHelper.editService(
                kubeHelper.getHostname(kubectlNamespace),
                pipeConfig.serviceTesting.name,
                quayBranchName
              )
              testedEnv = kubeHelper.getHostname(kubectlNamespace)
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

      if (pipeConfig.MANIFEST != null && (pipeConfig.MANIFEST == true || pipeConfig.MANIFEST == "True")) {
        // Setup stages for MANIFEST builds
        stage('SelectNamespace') {
         try {
          if(!doNotRunTests) {
            (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(pipeConfig['UID'], namespaces)
            kubeLocks << lock
          } else {
            Utils.markStageSkippedForConditional(STAGE_NAME)
          }
         } catch (ex) {
           metricsHelper.writeMetricWithResult(STAGE_NAME, false)
           throw ex
         }
	 currentBuild.displayName = "#${BUILD_NUMBER} - ${kubectlNamespace}"
         metricsHelper.writeMetricWithResult(STAGE_NAME, true)
        }
        stage('CleanUp3rdPartyResources') {
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
        stage('ModifyManifest') {
         try {
          if(!doNotRunTests) {
            if (isNightlyBuild == "true") {
              testedEnv = kubeHelper.getHostname(kubectlNamespace)
            } else {
              testedEnv = manifestHelper.manifestDiff(kubectlNamespace)
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

      stage('K8sReset') {
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

      stage('VerifyClusterHealth') {
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
      stage('GenerateData') {
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
      stage('FetchDataClient') {
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
      if(!runParallelTests) {
        stage('RunTests') {
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
      } else {
        stage('runNonConcurrentStuff') {
          try {
            if(!doNotRunTests) {
              testHelper.runScriptToCreateProgramsAndProjects(kubectlNamespace)
              if (selectedTests.contains("all")) {
                selectedTests = testHelper.gatherAllTestSuiteLabels(kubectlNamespace)
              }
              env.GEN3_SKIP_PROJ_SETUP = "true"
            } else {
              Utils.markStageSkippedForConditional(STAGE_NAME)
            }
          } catch (ex) {
            metricsHelper.writeMetricWithResult(STAGE_NAME, false)
            throw ex
          }
          metricsHelper.writeMetricWithResult(STAGE_NAME, true)
        }

        def testsToParallelize = [:]
        List<String> failedTestSuites = [];

        selectedTests.each {selectedTest ->
          parallelStageName = selectedTest.replace("/", "-")
          testsToParallelize["parallel-${parallelStageName}"] = {
            stage('RunTest') {
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

        parallel testsToParallelize

        stage('ProcessCIResults') {
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

      stage('CleanS3') {
       try {
        if(!doNotRunTests) {
          testHelper.cleanS3(kubectlNamespace)
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
    catch (e) {
      pipelineHelper.handleError(e)
      throw e
    }
    finally {
      stage('Post') {
        kubeHelper.teardown(kubeLocks)
        testHelper.teardown(doNotRunTests)
        pipelineHelper.teardown(currentBuild.result)
      }
    }
  }
}
