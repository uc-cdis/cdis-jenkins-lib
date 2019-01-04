#!groovy

def call(Map config) {
  stages {
    stage('WaitForQuayBuild') {
        when {
          expression { env.JOB_NAME != 'cdis-manifest' }
        }
        steps {
          script {
            waitForQuay(config)
          }
        }
      }
      stage('SelectNamespace') {
        when {
          expression { env.JOB_NAME != 'cdis-manifest' }
        }
        steps {
          script {
            selectNamespace(config))
          }
        }
      }
      stage('ModifyManifest') {
        when {
          expression { env.JOB_NAME != 'cdis-manifest' }
        }
        steps {
          script {
            dirname = sh(script: "kubectl -n $env.KUBECTL_NAMESPACE get configmap global -o jsonpath='{.data.hostname}'", returnStdout: true)
          }
          dir("cdis-manifest/$dirname") {
            withEnv(["masterBranch=$env.service:[a-zA-Z0-9._-]*", "targetBranch=$env.service:$env.quaySuffix"]) {
              sh 'sed -i -e "s,'+"$env.masterBranch,$env.targetBranch"+',g" manifest.json && cat manifest.json'
            }
          }
        }
      }
  }
}
