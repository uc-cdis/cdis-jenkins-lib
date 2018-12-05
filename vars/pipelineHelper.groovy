
def create(Map config) {
  this.config = init(config)
  // conf = config
  this.kube = kubeHelper.create(this.config)
  this.git = gitHelper.create(this.config)
  this.test = testRunner.create(this.config)

  return this
}

def init(Map config) {
  gitVars = checkout(scm)
  if (null == config || !config.containsKey('GIT_BRANCH')) {
    config.GIT_BRANCH = gitVars.GIT_BRANCH
    config.GIT_COMMIT = gitVars.GIT_COMMIT
  }
  config.branchFormatted = "${config.GIT_BRANCH}".replaceAll("/", "_")

  if (null == config || !config.containsKey('JOB_NAME')) {
    config.JOB_NAME = "$env.JOB_NAME".split('/')[1]
  }
  config.UID = "${config.JOB_NAME}-${config.BRANCH_FORMATTED}-${env.BUILD_NUMBER}"

  return config
}