#!groovy

//Library('cdis-jenkins-lib@fix/gitops-qa') _

// See 'Loading libraries dynamically' here: https://jenkins.io/doc/book/pipeline/shared-libraries/
library identifier: "cdis-jenkins-lib@${env.CHANGE_BRANCH}"

testPipeline {
  // overrides for testing the ModifyManifest stage ...
  JOB_NAME = 'indexd'
  GIT_BRANCH = 'master'
}
