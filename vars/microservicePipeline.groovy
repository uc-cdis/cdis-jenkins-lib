#!groovy

def call(Map config) {
  KubeHelper helper
  pipeline {
    agent any
  
    environment {
      QUAY_API = 'https://quay.io/api/v1/repository/cdis/'
    }
  
    stages {
      stage('FetchCode') {
        steps {
          script {
            fetchCode()
            env.service = "$env.JOB_NAME".split('/')[1]
            env.quaySuffix = "$env.GIT_BRANCH".replaceAll("/", "_")
          }
        }
      }
      stage('PrepForTesting') {
        steps {
          script {
            // caller overrides of the service image to deploy
            if (config) {
              if (config.JOB_NAME) {
                env.service = config.JOB_NAME
              }
              if (config.GIT_BRANCH) {
                env.quaySuffix = config.GIT_BRANCH
              }
            }
          }
        }
      }
      stage('WaitForQuayBuild') {
        when {
          branch 'dontrun'
        }
        steps {
          script {
            waitForQuayBuild(config)
          }
        }
      }
      stage('SelectNamespace') {
        steps {
          script {
            selectAndLockNamespace(config)
          }
        }
      }
      stage('ModifyManifest') {
        steps {
          script {
            dirname = sh(script: "kubectl -n $env.KUBECTL_NAMESPACE get configmap global -o jsonpath='{.data.hostname}'", returnStdout: true)
          }
          dir("cdis-manifest/$dirname") {
            withEnv(["masterBranch=$env.service:[a-zA-Z0-9._-]*", "targetBranch=$env.service:$env.quaySuffix"]) {
              sh 'sed -i -e "s,'+"$env.masterBranch,$env.targetBranch"+',g" manifest.json'
            }
          }
        }
      }
      stage('K8sDeploy') {
        steps {
          script {
            kubeDeploy()
          }
        }
      }
      stage('RunTests') {
        steps {
          script {
            runIntegrationTests()
          }
        }
      }
    }
    post {
      success {
        echo "https://jenkins.planx-pla.net/ $env.JOB_NAME pipeline succeeded"
      }
      failure {
        echo "Failure!"
        archiveArtifacts artifacts: '**/output/*.png', fingerprint: true
        //slackSend color: 'bad', message: "https://jenkins.planx-pla.net $env.JOB_NAME pipeline failed"
      }
      unstable {
        echo "Unstable!"
        //slackSend color: 'bad', message: "https://jenkins.planx-pla.net $env.JOB_NAME pipeline unstable"
      }
      always {
        script {
          uid = env.service+"-"+"$env.GIT_BRANCH".replaceAll("/", "_")+"-"+env.BUILD_NUMBER
          klockNamespace( method: 'unlock', uid: uid )
        }
        echo "done"
        junit "gen3-qa/output/*.xml"
      }
    }
  }
}