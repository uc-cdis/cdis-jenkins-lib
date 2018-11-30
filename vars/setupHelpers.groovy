
def call(config) {
  gitVars = checkout(scm)
  if (config & !config.GIT_BRANCH) {
    config.GIT_BRANCH = gitVars.GIT_BRANCH
    config.GIT_COMMIT = gitVars.GIT_COMMIT
  }
  config.BRANCH_FORMATTED = "${config.GIT_BRANCH}".replaceAll("/", "_")

  if (config && !config.JOB_NAME) {
    config.JOB_NAME = "$env.JOB_NAME".split('/')[1]
  }
  config.UID = "${config.JOB_NAME}-${config.BRANCH_FORMATTED}-${env.BUILD_NUMBER}"
}