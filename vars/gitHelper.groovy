
def setup() {
  vars = checkout(scm)
}

def getBranch() {
  return vars.GIT_BRANCH
}

/**
* Pulls common repositories used for testing
*/
def fetchAllRepos() {
  echo "my vars: ${vars}"
  dir('gen3-qa') {
    git(
      url: 'https://github.com/uc-cdis/gen3-qa.git',
      branch: 'master'
    )
  }
  dir('data-simulator') {
    git(
      url: 'https://github.com/occ-data/data-simulator.git',
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