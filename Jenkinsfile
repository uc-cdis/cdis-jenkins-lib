#!groovy

// See 'Loading libraries dynamically' here: https://jenkins.io/doc/book/pipeline/shared-libraries/
library identifier: "cdis-jenkins-lib@${env.CHANGE_BRANCH}"

testPipeline {
  MANIFEST = false
  VPCNAME = "qaplanetv1"
  quayRegistry = "jenkins-lib"
}
