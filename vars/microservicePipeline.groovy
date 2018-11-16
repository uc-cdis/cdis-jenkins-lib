#!groovy

def call(Map config) {
  pipeline {
    agent any
  
    stages {
      stage('FetchCode') {
        steps {
          script {
            fetchCode()
          }
        }
      }
      stage('WaitForQuayBuild') {
        when {
          branch 'dontrun'
        }
        steps {
          script {
            waitForQuayBuild(getService())
          }
        }
      }
      stage('SelectNamespace') {
        steps {
          script {
            selectAndLockNamespace( namespaces: config.namespaces, uid: getUid() )
          }
        }
      }
      stage('ModifyManifest') {
        steps {
          script {
            dirname = sh(script: "kubectl -n $env.KUBECTL_NAMESPACE get configmap global -o jsonpath='{.data.hostname}'", returnStdout: true)
          }
          dir("cdis-manifest/$dirname") {
            quaySuffix = getQuaySuffix(config)
            withEnv(["masterBranch=$env.service:[a-zA-Z0-9._-]*", "targetBranch=$env.service:$quaySuffix"]) {
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
          klockNamespace( method: 'unlock', uid: getUid() )
        }
        echo "done"
        junit "gen3-qa/output/*.xml"
      }
    }
  }
}

def getService(config) {
  if (config.JOB_NAME) {
    return config.JOB_NAME
  }
  return "$env.JOB_NAME".split('/')[1]
}

def getQuaySuffix(config) {
  if (config) {
    if (config.GIT_BRANCH) {
      return config.GIT_BRANCH
    }
  }
  return "$env.GIT_BRANCH".replaceAll("/", "_")
}

def getUid(config) {
  return getService(config)+"-"+"$env.GIT_BRANCH".replaceAll("/", "_")+"-"+env.BUILD_NUMBER
}