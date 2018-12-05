import groovy.transform.Field

@Field def config // pipeline config shared between helpers
@Field def kube // kubernetes/commons helper
@Field def git // performs git tasks
@Field def test // runs tests and creates test data

def create(Map config) {
  this.config = setupConfig(config)
  this.kube = kubeHelper.create(this.config)
  this.git = gitHelper.create(this.config)
  this.test = testRunner.create(this.config)
  this.quay = quayHelper.create(this.config)

  return this
}

/**
* Updates config by adding some missing and new values to the map
*/
def setupConfig(Map config) {
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