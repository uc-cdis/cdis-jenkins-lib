def setGitEnvVars() {
  gitVars = checkout(scm)
  for (e in gitVars) {
    println("key = ${e.key}, value = ${e.value}")
    env[e.key] = e.value
  }
}

/**
* Pulls common repositories used for testing
*/
def fetchAllRepos(String currentRepoName) {
  setGitEnvVars()
  sh("env")
  dir('gen3-qa') {
    if (currentRepoName == "gen3-qa") {
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

/**
* Returns a map of recent changes
*/
def getLatestChangeOfBranch(String branchName=env.CHANGE_BRANCH) {
  if (null == branchName) {
    error("unable to determine branch");    
  }
  sh("git config --add remote.origin.fetch +refs/heads/master:refs/remotes/origin/master")
  sh("git fetch --no-tags")
  List<String> changes = sh(returnStdout: true, script: "git diff --name-only origin/master...$branchName").split()
  HashMap fileChanges = [:]
  def size = changes.size()
  for (int i = 0; i < size; i++) {
      def dirs = changes[i].split('/')
      def k = '.'
      if (dirs.size() > 1)
          k = dirs[0]
      if (!fileChanges.containsKey(dirs[0]))
          fileChanges[k] = []
      fileChanges[k].add(changes[i])
  }
  return fileChanges
}