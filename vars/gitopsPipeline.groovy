#!groovy

def call(Map config) {
  pipeline {
    agent any
  
    stages {
      stage('FetchCode') {
        steps {
          script {
            fetchCode()
            // checkout master branch of cdis-manifest - used for comparing files to determine if a manifest was edited
            dir('cdis-manifest-master') {
              git(
                url: 'https://github.com/uc-cdis/cdis-manifest.git',
                branch: 'master'
              )
            }
            env.service = "$env.JOB_NAME".split('/')[1]
            env.uid = env.service+"-"+"$env.GIT_BRANCH".replaceAll("/", "_")+"-"+env.BUILD_NUMBER
          }
        }
      }
      stage('DetectChanges') {
        steps {
          script {
            env.ABORT_SUCCESS = 'true'
            env.KUBECTL_NAMESPACE = 'qa-bloodpac'

            // get all paths to commons manifests
            def manifestFiles = findFiles(glob: 'cdis-manifest/*/manifest.json')
            for (int i = 0; i < manifestFiles.length; i++) {
              // check if master branch also has the manifest
              def master_path = manifestFiles[i].path.replaceAll('cdis-manifest', 'cdis-manifest-master')
              if (fileExists(master_path)) {
                // check if the manifest files are the same
                def cmpRes = sh( script: "cmp ${manifestFiles[i].path} ${master_path} || true", returnStdout: true )
                // if the comparison result is not empty then the files are different, use this manifest for testing!
                if (cmpRes != '') {
                  env.ABORT_SUCCESS = 'false'
                  env.AFFECTED_PATH = manifestFiles[i].path.replaceAll('cdis-manifest/', '')
                  env.KUBECTL_NAMESPACE = 'default'
                  break
                }
              }
            }
          }
        }
      }
      // successful exit early if we don't find any manifest changes
      if (env.JENKINS_MANIFEST_CHANGED == 'false') {
        return
      }
      stage('SubstituteManifest') {
        steps {
          // try to lock down KUBECTL_NAMESPACE
          // if unable to lock, fail
          def uid = BUILD_TAG.replaceAll(' ', '_').replaceAll('%2F', '_')
          def lockRes = klockNamespace( method: 'lock', uid: uid )
          if (lockRes != 0) {
            error("aborting - unable to lock "+env.KUBECTL_NAMESPACE)
          }

          // copy over the new manifest
          script {
            dirname = sh(script: "kubectl -n $env.KUBECTL_NAMESPACE get configmap global -o jsonpath='{.data.hostname}'", returnStdout: true)
          }
          dir('cdis-manifest') {
            withEnv(["fromPath=$env.AFFECTED_PATH", "toPath=$dirname/manifest.json"]) {
              sh "mkdir -p $dirname"
              sh "cp $env.fromPath $env.toPath"
            }
          }
        }
      }
      stage('K8sDeploy') {
        script {
          kubeDeploy()
        }
      }
      stage('RunTests') {
        when {
          environment name: 'ABORT_SUCCESS', value: 'false'
        }
        script {
          runIntegrationTests()
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
          klockNamespace( method: 'lock', uid: env.uid )
        }
        echo "done"
        junit "gen3-qa/output/*.xml"
      }
    }
  }
}