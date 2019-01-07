package uchicago.cdis;

/**
 * Groovy helper for deploying services to k8s in Jenkins pipelines.
 *
 * @see https://jenkins.io/doc/book/pipeline/shared-libraries/
 */
class GitHelper implements Serializable {
  def steps
  
  /**
   * Constructor
   *
   * @param steps injects hook to Jenkins Pipeline runtime
   */
  GitHelper(steps) {
    this.steps = steps;
  }

  def getLatestChangeOfBranch(String branchName=steps.env.CHANGE_BRANCH) {
    if (null == branchName) {
      throw new IllegalStateException("unable to determine branch");    
    }
    steps.sh "git config --add remote.origin.fetch +refs/heads/master:refs/remotes/origin/master"
    steps.sh "git fetch --no-tags"
    List<String> changes = steps.sh(returnStdout: true, script: "git diff --name-only origin/master...$branchName").split()
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
}
