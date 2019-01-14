#!groovy

/**
* Pipline for building and testing microservices
* 
* @param config - pipeline configuration
*/
def call(Map config) {
  node {
    pipe = pipelineHelper.create(config)
    try {
      stage('FetchCode') {
        pipe.git.fetchAllRepos()
      }
      if (!pipe.config.skipQuay) {
        stage('WaitForQuayBuild') {
          pipe.quay.waitForBuild()
        }
      }
      stage('SelectNamespace') {
        pipe.kube.selectAndLockNamespace()
      }
      stage('ModifyManifest') {
        pipe.manifest.editService(
          pipe.kube.getHostname(),
          pipe.config.serviceTesting.name,
          pipe.config.serviceTesting.branch
        )
      }
      stage('K8sReset') {
        pipe.kube.reset()
      }
      stage('VerifyClusterHealth') {
        pipe.kube.waitForPods()
        pipe.test.checkPodHealth()
      }
      stage('GenerateData') {
        pipe.test.simulateData(pipe.kube.kubectlNamespace)
      }
      stage('FetchDataClient') {
        pipe.test.fetchDataClient()
      }
      stage('FetchDataClient') {
        steps {
          dir('dataclient') {
            script {
              // we get the data client from master, unless the service being
              // tested is the data client itself, in which case we get the
              // executable for the current branch
              // Note: the data client does not use Jenkins yet (see PXP-2211)
              branch = "master"
              if (env.service == "cdis-data-client") {
                branch = env.CHANGE_BRANCH
                println "Testing cdis-data-client on branch " + branch
              }

              // Note: at this time, tests are always run on linux
              os = "linux"

              // download the gen3 data client executable from S3
              download_location = "dataclient.zip"
              sh String.format("aws s3 cp s3://cdis-dc-builds/%s/dataclient_%s.zip %s", branch, os, download_location)
              assert fileExists(download_location)
              unzip(download_location)

              // make sure we can execute it
              executable_name = "gen3-client"
              assert fileExists(executable_name)
              sh "mv $executable_name $env.WORKSPACE/$executable_name"
              sh "chmod u+x $env.WORKSPACE/$executable_name"
              sh "$env.WORKSPACE/$executable_name --version"

              println "Data client successfully set up at: $env.WORKSPACE/$executable_name"
            }
          }
        }
      }
      stage('RunTests') {
        pipe.test.runIntegrationTests(
          pipe.kube.kubectlNamespace,
          pipe.config.serviceTesting.name
        )
      }
    }
    catch (e) {
      pipe.handleError(e)
    }
    finally {
      stage('Post') {
        pipe.teardown(currentBuild.result)
      }
    }
  }
}
