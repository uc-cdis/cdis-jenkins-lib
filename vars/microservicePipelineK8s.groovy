#!groovy

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

/**
* Pipline for building and testing microservices
* 
* @param config - pipeline configuration
*/
def call(Map config) {

  def AVAILABLE_NAMESPACES = ['jenkins-blood', 'jenkins-brain', 'jenkins-niaid', 'jenkins-dcp', 'jenkins-genomel']
  List<String> namespaces = []
  List<String> selectedTests = []
  doNotRunTests = false
  doNotModifyManifest = false
  isGen3Release = "false"
  isNightlyBuild = "false"
  selectedTest = "all"
  prLabels = null
  kubectlNamespace = null
  kubeLocks = []
  testedEnv = "" // for manifest pipeline
  pipeConfig = pipelineHelper.setupConfig(config)
  pipelineHelper.cancelPreviousRunningBuilds()
  prLabels = githubHelper.fetchLabels()

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
    image: quay.io/cdis/jenkins:master
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
  serviceAccount: gen3-self-service-account
  serviceAccountName: gen3-self-service-account
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
	          case "decommission-environment":
	     	    println('Skip tests if an environment folder is deleted')
	     	    doNotRunTests = doNotRunTests || decommissionEnvHelper.checkDecommissioningEnvironment()
	          case "commission-environment":
	     	    println('Skip ModifyManifest step to introduce a new CI environment')
	     	    doNotModifyManifest = true
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
	    } catch (e) {
	      pipelineHelper.handleError(e)
	    }
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
                  quayHelper.waitForBuild(
                    pipeConfig['quayRegistry'],
                    pipeConfig['currentBranchFormatted']
                  )
		} else {
                  Utils.markStageSkippedForConditional(STAGE_NAME)
                }
	      } else {
	        Utils.markStageSkippedForConditional(STAGE_NAME)
	      }
	    } catch (e) {
	      pipelineHelper.handleError(e)
	    }
	  }
	}
      }
      stage('SelectNamespace') {
	steps {
	  script {
	    try {
	      if(!doNotRunTests) {
	        (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(pipeConfig['UID'], namespaces)
		kubeLocks << lock
	      } else {
	        Utils.markStageSkippedForConditional(STAGE_NAME)
	      }
	    } catch (e) {
	      pipelineHelper.handleError(e)
	    }
	  }
        }
      }
      stage('CleanUp3rdPartyResources') {
        steps {
          script {
            if(!doNotRunTests) {
              testHelper.deleteGCPServiceAccounts(kubectlNamespace)
            } else {
              Utils.markStageSkippedForConditional(STAGE_NAME)
            }
          }
        }
      }
      stage('ModifyManifest') {
        steps {
          script {
            try {
              if(!doNotRunTests && !doNotModifyManifest) {
                if (pipeConfig.MANIFEST == null || pipeConfig.MANIFEST == false || pipeConfig.MANIFEST != "True") {
                  manifestHelper.editService(
                    kubeHelper.getHostname(kubectlNamespace),
                    pipeConfig.serviceTesting.name,
                    pipeConfig.serviceTesting.branch
                  )
                } else {
                  testedEnv = manifestHelper.manifestDiff(kubectlNamespace)
                }
              } else {
                Utils.markStageSkippedForConditional(STAGE_NAME)
              }
            } catch (e) {
              pipelineHelper.handleError(e)
            }
	  }
	}
      }
      stage('K8sReset') {
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
            } catch (e) {
              pipelineHelper.handleError(e)
            }
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
            } catch (e) {
	      pipelineHelper.handleError(e)
	    }
	  }
	}
      }
      stage('GenerateData') {
        steps {
	  script {
	    try {
	      if(!doNotRunTests) {
	        testHelper.simulateData(kubectlNamespace)
	      } else {
	        Utils.markStageSkippedForConditional(STAGE_NAME)
	      }
            } catch (e) {
	      pipelineHelper.handleError(e)
	    }
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
	    } catch (e) {
	      pipelineHelper.handleError(e)
	    }
	  }
	}
      }
      stage('RunTests') {
        steps {
	  script {
	    try {
	      if(!doNotRunTests) {
	        testHelper.runIntegrationTests(
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
            } catch (e) {
	      pipelineHelper.handleError(e)
	    }
	  }
	}
      }
      stage('CleanS3') {
        steps {
          script {
            try {
	      if(!doNotRunTests) {
	        testHelper.cleanS3()
	      } else {
	        Utils.markStageSkippedForConditional(STAGE_NAME)
	      }
	    } catch (e) {
	      pipelineHelper.handleError(e)
	    }
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
