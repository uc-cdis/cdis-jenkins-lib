#!groovy

//Library('cdis-jenkins-lib@fix/gitops-qa') _

// See 'Loading libraries dynamically' here: https://jenkins.io/doc/book/pipeline/shared-libraries/
library identifier: "cdis-jenkins-lib@${env.BRANCH_NAME}"

runPipeline {
  pipeline = 'microservice'
  serviceTesting = [service: 'fence', branch: 'master']
  waitForQuay = false
}
