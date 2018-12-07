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
    try {
      stage('FetchCode') {
        pipe.git.fetchAllRepos()
        // fetch master branch of this repo to detect manifest edits later
        // pipe.git.checkoutBranch('master', 'cdis-manifest-master')

        // testing a manifest - check out the current branch here
        // println("INFO: checkout manifests from ${this.config.currentRepoName}'s branch ...\n  ${this.config.gitVars.GIT_URL}\n  ${this.config.gitVars.GIT_COMMIT}")
        dir (pipe.config.currentRepoName) {
          checkout scm
        }
      }
      stage('DetectManifestChanges') {
        affectedManifests = pipe.manifest.getAffectedManifests('cdis-manifest', pipe.config.currentRepoName)
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
        thisNamespaceManifestDir = pipe.kube.getHostname()
        dest = "cdis-manifest/${thisNamespaceManifestDir}/"
        sh("cp ${source} ${dest}")
      }
      stage('K8sDeploy') {
        pipe.kube.deploy()
      }
      stage('GenerateData') {
        pipe.test.simulateData(pipe.kube.kubectlNamespace)
      }
      stage('RunTests') {
        pipe.test.runIntegrationTests(pipe.kube.kubectlNamespace, pipe.config.serviceTesting)
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