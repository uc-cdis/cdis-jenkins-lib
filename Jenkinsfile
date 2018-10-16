#!groovy

//Library('cdis-jenkins-lib@fix/gitops-qa') _

// See 'Loading libraries dynamically' here: https://jenkins.io/doc/book/pipeline/shared-libraries/
echo "Loading cdis-jenkins-lib@${env.BRANCH_NAME}"
library identifier: "cdis-jenkins-lib@${env.BRANCH_NAME}" //, retriever: legacySCM(scm)

testPipeline { 
  JOB_NAME = 'fence'
  GIT_BRANCH = 'master'
}
