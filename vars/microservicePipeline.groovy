#!groovy

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
    prLabels = null
    kubectlNamespace = null
    kubeLocks = []
    testedEnv = "" // for manifest pipeline
    pipeConfig = pipelineHelper.setupConfig(config)
    pipelineHelper.cancelPreviousRunningBuilds()
    prLabels = githubHelper.fetchLabels()
    try {
      stage('CheckPRLabels') {
        for(label in prLabels) {
          println(label['name']);
          switch(label['name']) {
            case "doc-only":
              println('TODO: Skip tests')
	      isDocumentationOnly = true
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
      stage('FetchCode') {
        when {
          expression { isDocumentationOnly == false }
        }
	steps {
          gitHelper.fetchAllRepos(pipeConfig['currentRepoName'])
	}
      }
      if (pipeConfig.MANIFEST == null || pipeConfig.MANIFEST == false || pipeConfig.MANIFEST != "True") {
        // Setup stages for NON manifest builds
        stage('WaitForQuayBuild') {
          quayHelper.waitForBuild(
            pipeConfig['quayRegistry'],
            pipeConfig['currentBranchFormatted']
          )
        }
        stage('SelectNamespace') {
	  when {
            expression { isDocumentationOnly == false }
          }
	  steps {
            (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(pipeConfig['UID'], namespaces)
            kubeLocks << lock
	  }
        }
        stage('ModifyManifest') {
	  when {
            expression { isDocumentationOnly == false }
          }
	  steps {
            manifestHelper.editService(
              kubeHelper.getHostname(kubectlNamespace),
              pipeConfig.serviceTesting.name,
              pipeConfig.serviceTesting.branch
            )
	  }
        }
      }

      if (pipeConfig.MANIFEST != null && (pipeConfig.MANIFEST == true || pipeConfig.MANIFEST == "True")) {
        // Setup stages for MANIFEST builds
        stage('SelectNamespace') {
	  when {
            expression { isDocumentationOnly == false }
          }
	  steps {
            (kubectlNamespace, lock) = kubeHelper.selectAndLockNamespace(pipeConfig['UID'], namespaces)
            kubeLocks << lock
	  }
        }
        stage('ModifyManifest') {
	  when {
            expression { isDocumentationOnly == false }
          }
	  steps {
            testedEnv = manifestHelper.manifestDiff(kubectlNamespace)
          }
      }

      stage('K8sReset') {
        when {
          expression { isDocumentationOnly == false }
        }
        steps {
          // adding the reset-lock lock in case reset fails before unlocking
          kubeLocks << kubeHelper.newKubeLock(kubectlNamespace, "gen3-reset", "reset-lock")
          kubeHelper.reset(kubectlNamespace)
	}
      }
      stage('VerifyClusterHealth') {
        when {
          expression { isDocumentationOnly == false }
        }
	steps {
          kubeHelper.waitForPods(kubectlNamespace)
          testHelper.checkPodHealth(kubectlNamespace)
	}
      }
      stage('GenerateData') {
        when {
          expression { isDocumentationOnly == false }
        }
	steps {
          testHelper.simulateData(kubectlNamespace)
        }
      }
      stage('FetchDataClient') {
        when {
          expression { isDocumentationOnly == false }
        }
	steps {
          // we get the data client from master, unless the service being
          // tested is the data client itself, in which case we get the
          // executable for the current branch
          dataCliBranch = "master"
          if (pipeConfig.currentRepoName == "cdis-data-client") {
            dataCliBranch = env.CHANGE_BRANCH
          }
          testHelper.fetchDataClient(dataCliBranch)
	}
      }
      stage('RunTests') {
        when {
          expression { isDocumentationOnly == false }
        }
        steps {
          testHelper.runIntegrationTests(
            kubectlNamespace,
            pipeConfig.serviceTesting.name,
            testedEnv
          )
	}
      }
      stage('CleanS3') {
        when {
          expression { isDocumentationOnly == false }
        }
	steps {
          testHelper.cleanS3()
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
