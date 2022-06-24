/**
* Fetch some git values and set environment variables
* It appears that when using scripted pipelines,
* checkout scm does not set env vars.
*/
def setGitEnvVars(String currentRepoName) {
  dir('tmpGitClone') {
    (gitCommit, gitPreviousCommit) = sh(script: 'git log --author=Jenkins --invert-grep -10 --pretty="format: %h"', returnStdout: true).split('\n')
    println(gitCommit)
    println(gitPreviousCommit)
    env.GIT_COMMIT = gitCommit.trim()
    env.GIT_PREVIOUS_COMMIT = gitPreviousCommit.trim()
  }
}

/**
* Pulls common repositories used for testing
*/
def fetchAllRepos(String currentRepoName) {
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
    if (currentRepoName == "data-simulator") {
      // testing the data-simulator repo - check out the test branch here
      println("INFO: checkout data-simulator/ from JOB repo branch ...")
      checkout scm;
    } else {
      git(
        url: 'https://github.com/uc-cdis/data-simulator.git',
        branch: 'master'
      );
    }
  }
  dir('cdis-manifest') {
    if (currentRepoName.endsWith("v2")) {
      git(
        url: 'https://github.com/uc-cdis/gitops-qa-v2.git',
        branch: 'master'
      )
    } else {
      git(
        url: 'https://github.com/uc-cdis/gitops-qa.git',
        branch: 'master'
      )
    }
  }
  dir('cloud-automation') {
    if (currentRepoName == "cloud-automation") {
      // testing the data-simulator repo - check out the test branch here
      println("INFO: checkout cloud-automation/ from JOB repo branch ...")
      checkout scm;
    } else {
      git(
        url: 'https://github.com/uc-cdis/cloud-automation.git',
        branch: 'master'
      );
    }
  }
  dir('tmpGitClone') {
    checkout(scm: scm, clearWorkspace: true)
  }

  setGitEnvVars(currentRepoName)
}

/**
* Returns a map of recent changes
*/
def getLatestChangeOfBranch(String branchName=env.CHANGE_BRANCH) {
  dir('tmpGitClone') {
    if (null == branchName) {
      error("unable to determine branch");
    }
    sh("git config --add remote.origin.fetch +refs/heads/master:refs/remotes/origin/master")
    sh("git fetch --no-tags")
    List<String> changes = sh(returnStdout: true, script: "git diff --name-only origin/master...$branchName").split()
    HashMap fileChanges = [:]
    def size = changes.size()
    for (int i = 0; i < size; i++) {
      println(changes[i])
        def dirs = changes[i].split('/')
        def k = '.'
        if (dirs.size() > 1)
            k = dirs[0]
        if (!fileChanges.containsKey(dirs[0]))
            fileChanges[k] = []
        fileChanges[k].add(changes[i])
    }
    println(fileChanges)
    println("Branchname: ${branchName}")
    sh("git log -10")
    sh("git rev-parse HEAD")
    sh("git diff --name-only origin/master...$branchName")

    return fileChanges
  }
}

/**
* Returns the timestamp of the latest commit
*/
def getTimestampOfLatestCommit(String branchName=env.CHANGE_BRANCH) {
  dir('tmpGitClone') {
    if (null == branchName) {
      error("unable to determine branch");
    }
    sh("git config --add remote.origin.fetch +refs/heads/master:refs/remotes/origin/master")
    sh("git fetch --no-tags")
    println("Branchname: ${branchName}")
    String ts = sh(returnStdout: true, script: "git log origin/$branchName -1 --format=%ct").trim()

    return ts
  }
}
