#!groovy

def call(Map config) {
  pipeline {
    agent any

    environment {
    }
  
    stages {
      stage('FetchCode') {
        steps {
          script {
            fetchCode()
            env.service = "$env.JOB_NAME".split('/')[1]
          }
        }
      }
      stage('DetectChanges') {
        steps {
          script {
            List<String> commonsList = config.namespaces

            // search the change logs for a manifest edit
            def changeLogSets = currentBuild.changeSets
            def foundMatch=false;
            for (int i = 0; !foundMatch && i < changeLogSets.size(); i++) {
              def entries = changeLogSets[i].items
              // for each entry, check it's affected paths to see if the manifest of a commons was edited
              for (int j = 0; !foundMatch && j < entries.length; j++) {
                def affectedPaths = entries[j].getAffectedPaths()
                for (int k = 0; !foundMatch && k < commonsList.size(); k++) {
                  def commonsManifest = commonsList.get(k)+'/manifest.json'
                  if (affectedPaths.contains(commonsManifest) {
                    env.AFFECTED_PATH = commonsManifest
                    env.KUBECTL_NAMESPACE = commonsList.get(k)
                    env.COPY_MANIFEST = 'true';
                    foundMatch = true;
                  }
                }
                if (!foundMatch) { println "ignoring git changes to: " + affectedPaths.join("\n") }
              }
            }

            if (!foundMatch) {
              // nothing to test!
              println "testable stuff was not affected, aborting"
              env.ABORT_SUCCESS = 'true';
              env.COPY_MANIFEST = 'false';
              currentBuild.result = 'SUCCESS'
              env.JENKINS_MANIFEST_CHANGED = 'false'
            } else {
              // there's something to test!
              env.ABORT_SUCCESS = 'false';
              env.JENKINS_MANIFEST_CHANGED = 'true'
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
          uid = env.service+"-"+"$env.GIT_BRANCH".replaceAll("/", "_")+"-"+env.BUILD_NUMBER
          klockNamespace( method: 'lock', uid: uid )
        }
        echo "done"
        junit "gen3-qa/output/*.xml"
      }
    }
  }
}