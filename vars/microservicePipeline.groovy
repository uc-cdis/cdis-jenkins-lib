#!groovy

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

/**
* Pipline for building and testing microservices
* 
* @param config - pipeline configuration
*/
def call(Map config) {
  node('master') {
    def AVAILABLE_NAMESPACES = ciEnvsHelper.fetchCIEnvs()
    List<String> namespaces = []
    List<String> selectedTests = []
    doNotRunTests = false
    runParallelTests = false
    isGen3Release = "false"
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
       try {
        // giving a chance for auto-label gh actions to catch up
        sleep(30)
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
            case "parallel-testing":
              println('Run labelled test suites in parallel')
              runParallelTests = true
              break
            case "decommission-environment":
              println('Skip tests if an environment folder is deleted')
              doNotRunTests = decommissionEnvHelper.checkDecommissioningEnvironment()
            case "gen3-release":
              println('Enable additional tests and automation')
              isGen3Release = "true"
              break
            case "nightly-run":
              println('Enable additional tests and automation for our nightly-release')
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
        }
       } catch (ex) {
        metricsHelper.writeMetricWithResult(STAGE_NAME, false)  
        throw ex
       }
       metricsHelper.writeMetricWithResult(STAGE_NAME, true)
      }
      if (pipeConfig.MANIFEST == null || pipeConfig.MANIFEST == false || pipeConfig.MANIFEST != "True") {
        // Setup stages for NON manifest builds
        stage('WaitForQuayBuild') {
         try {
          if(!doNotRunTests) {
            quayHelper.waitForBuild(
              pipeConfig['quayRegistry'],
              pipeConfig['currentBranchFormatted']
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
              manifestHelper.editService(
                kubeHelper.getHostname(kubectlNamespace),
                pipeConfig.serviceTesting.name,
                pipeConfig.serviceTesting.branch
              )
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
            testedEnv = manifestHelper.manifestDiff(kubectlNamespace)
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
         metricsHelper.writeMetricWithResult(STAGE_NAME, false)
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
          selectedTestLabelSplit = selectedTest.split("/")
          selectedTestLabel = "test-" + selectedTestLabelSplit[1] + "-" + selectedTestLabelSplit[2]
          testsToParallelize["parallel-${selectedTestLabel}"] = {
            stage('RunTest') {
              try {
                if(!doNotRunTests) {
                  println("### ## selectedTestLabel: ${selectedTestLabel}");
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
              } catch (ex) {
                println("### ## adding to list of failedTestSuites: ${selectedTestLabel}");
                failedTestSuites.add(selectedTestLabel);
                metricsHelper.writeMetricWithResult(STAGE_NAME, false)
              }
            }
          }
        }

        parallel testsToParallelize

        stage('ProcessCIResults') {
          try {
            if(!doNotRunTests) {
              testHelper.processCIResults(kubectlNamespace, failedTestSuites)
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
        testHelper.teardown()
        pipelineHelper.teardown(currentBuild.result)
      }
    }
  }
}
