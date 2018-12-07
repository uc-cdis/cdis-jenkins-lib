import groovy.transform.Field

@Field def config // pipeline config shared between helpers
@Field def kube // kubernetes/commons helper
@Field def git // performs git tasks
@Field def test // runs tests and creates test data
@Field def quay // quay helper
@Field def manifest // operations regarding the manifest

def create(Map config) {
  this.config = setupConfig(config)
  this.kube = kubeHelper.create(this.config)
  this.git = gitHelper.create(this.config)
  this.test = testRunner.create(this.config)
  this.quay = quayHelper.create(this.config)
  this.manifest = manifestHelper.create(this.config)

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
    config.GIT_URL = gitVars.GIT_URL
  }
  config.branchFormatted = "${config.GIT_BRANCH}".replaceAll("/", "_")

  if (null == config || !config.containsKey('JOB_NAME')) {
    config.JOB_NAME = "$env.JOB_NAME".split('/')[1]
  }
  config.UID = "${config.JOB_NAME}-${config.branchFormatted}-${env.BUILD_NUMBER}"

  return config
}

def handleError(e) {
  def st = new StringWriter()
  e.printStackTrace(new PrintWriter(st))
  echo "ERROR: ${e.message}\n\nStackTrace:\n${st}"
  throw e
}

def teardown(String buildResult) {
  if ("UNSTABLE" == buildResult) {
    echo "Unstable!"
    // slack.sendUnstable()
  }
  else if ("FAILURE" == buildResult) {
    echo "Failure!"
    archiveArtifacts(artifacts: '**/output/*.png', fingerprint: true)
    // slack.sendFailure()
  }
  else if ("SUCCESS" == buildResult) {
    echo "Success!"
    // slack.sendSuccess()
  }

  // unlock the namespace
  this.kube.klock('unlock')
  if (this.test.startedIntegrationTests) {
    junit "gen3-qa/output/*.xml"
  }
}