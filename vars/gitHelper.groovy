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
    if (this.config.currentRepoName == "gen3-qa") {
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
      url: 'https://github.com/uc-cdis/data-simulator.git',
      branch: 'master'
    )
  }
  dir('cdis-manifest') {
    git(
      url: 'https://github.com/uc-cdis/gitops-qa.git',
      branch: 'master'
    )
  }
  dir('cloud-automation') {
    git(
      url: 'https://github.com/uc-cdis/cloud-automation.git',
      branch: 'master'
    )
  }
}

/**
* Clone branch of the current repository into the provided directory
* Used to get different manifest branches for finding changes
*
* @param branchName
* @param directoryName
*/
def checkoutBranch(String branchName, String directoryName) {
  dir(directoryName) {
    git(
      url: this.config.gitVars.GIT_URL,
      branch: branchName
    )
  }
}
