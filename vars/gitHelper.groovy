import groovy.transform.Field

@Field def config // pipeline config shared between helpers

/**
* Constructor
*/
def create(Map config) {
  this.config = config
  return this
}

/**
* Pulls common repositories used for testing
*/
def fetchAllRepos() {
  dir('gen3-qa') {
    if (this.config.JOB_NAME == "gen3-qa") {
      // testing the gen3-qa repo - check out the test branch here
      println("INFO: checkout gen3-qa/ from JOB repo branch ...")
      checkout scm;
    } else {
      git(
        url: 'https://github.com/uc-cdis/gen3-qa.git',
        branch: 'master'
      );
    }
  }
  dir('data-simulator') {
    git(
      url: 'https://github.com/occ-data/data-simulator.git',
      branch: 'master'
    )
  }
  dir('cdis-manifest') {
    if (this.config.pipeline == "gitops") {
      // testing a manifest - check out the test branch here
      println("INFO: checkout manifests from JOB_NAME's repo branch ...")
      checkout scm
    } else {
      git(
        url: 'https://github.com/uc-cdis/gitops-qa.git',
        branch: 'master'
      )
    }
  }
  dir('cloud-automation') {
    git(
      url: 'https://github.com/uc-cdis/cloud-automation.git',
      branch: 'master'
    )
  }
}

def checkoutBranch(String branchName, String directoryName) {
  dir(directoryName) {
    git(
      url: this.config.GIT_URL,
      branch: branchName
    )
  }
}
