#!groovy

/**
* Pipline for testing manifest edits
* 
* @param config - pipeline configuration
*/
def call(Map config) {
  node {
    pipe = pipelineHelper.create(config)
    def affectedManifests
    catchError {
      stage('FetchCode') {
        pipe.git.fetchAllRepos()
        // fetch master branch of this repo to detect manifest edits later
        pipe.git.checkoutBranch('master', 'cdis-manifest-master')
      }
      stage('DetectManifestChanges') {
        affectedManifests = pipe.manifest.getAffectedManifests('cdis-manifest-master', 'cdis-manifest')
        echo("AFFECTED MANIFESTS: ${affectedManifests}")
        if (affectedManifests.size() == 0) {
          // nothing to test
          return
        }
      }
      stage('SelectNamespace') {
        pipe.kube.selectAndLockNamespace()
      }
      stage('SubstituteManifest') {
        // copy over the new manifest
        source = affectedManifests[0]
        echo("Selected Manifest: ${source}")
        thisManifestDir = pipe.kube.getHostname()
        dest = "cdis-manifest/${thisManifestDir}/manifest.json"
        sh("mkdir -p ${thisManifestDir}")
        sh("cp ${source} ${dest}")
      }
      stage('K8sDeploy') {
        pipe.kube.deploy()
      }
      stage('GenerateData') {
        pipe.test.simulateData(pipe.kube.kubectlNamespace)
      }
      stage('RunTests') {
        pipe.test.runIntegrationTests(pipe.kube.kubectlNamespace, pipe.config.service)
      }
    }

    // Post Pipeline steps
    stage('Post') {
      def currentResult = currentBuild.result
      if ("UNSTABLE" == currentResult) {
        echo "Unstable!"
        // slack.sendUnstable()
      }
      else if ("FAILURE" == currentResult) {
        echo "Failure!"
        archiveArtifacts(artifacts: '**/output/*.png', fingerprint: true)
        // slack.sendFailure()
      }
      else if ("SUCCESS" == currentResult) {
        echo "Success!"
        // slack.sendSuccess()
      }

      // unlock the namespace
      pipe.kube.klock('unlock')
      echo "done"
      junit "gen3-qa/output/*.xml"
    }
  }
}