package uchicago.cdis

import uchicago.cdis.KubeT

class PipelineHelper implements Serializable {
  def steps
  def config
  def kube
  
  /**
   * Constructor
   *
   * @param steps injects hook to Jenkins Pipeline runtime
   */
  PipelineHelper(steps, Map config) {
    this.steps = steps
    if (null == config) {
      this.config = [:]
    } else {
      this.config = config
    }

    gitVars = checkout(scm)
    if (!this.config.containsKey('GIT_BRANCH')) {
      this.config.GIT_BRANCH = gitVars.GIT_BRANCH
      this.config.GIT_COMMIT = gitVars.GIT_COMMIT
    }
    this.config.BRANCH_FORMATTED = "${this.config.GIT_BRANCH}".replaceAll("/", "_")

    if (!this.config.containsKey('JOB_NAME')) {
      this.config.JOB_NAME = "$env.JOB_NAME".split('/')[1]
    }
    this.config.UID = "${this.config.JOB_NAME}-${this.config.BRANCH_FORMATTED}-${env.BUILD_NUMBER}"

    this.kube = new KubeT(null, this.config)
  }
}