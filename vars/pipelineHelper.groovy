
def create(Map config) {
  init(config)
  conf = config
  kube = kubeHelper.new(config)
  git = gitHelper.new(config)

  return this
}

def init(Map config) {
  gitVars = checkout(scm)
  if (null == config || !config.containsKey('GIT_BRANCH')) {
    config.GIT_BRANCH = gitVars.GIT_BRANCH
    config.GIT_COMMIT = gitVars.GIT_COMMIT
  }
  config.BRANCH_FORMATTED = "${config.GIT_BRANCH}".replaceAll("/", "_")

  if (null == config || !config.containsKey('JOB_NAME')) {
    config.JOB_NAME = "$env.JOB_NAME".split('/')[1]
  }
  config.UID = "${config.JOB_NAME}-${config.BRANCH_FORMATTED}-${env.BUILD_NUMBER}"
}