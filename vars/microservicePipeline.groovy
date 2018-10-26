#!groovy

def call(Map config) {
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
              println "set test mock environment variables"
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
          config.skipDeploy 'false'
        }
        steps {
          script {
            service = "$env.JOB_NAME".split('/')[1]
            if (service == 'cdis-jenkins-lib') {
              service = 'jenkins-lib'
            }
            def timestamp = (("${currentBuild.timeInMillis}".substring(0, 10) as Integer) - 60)
            def timeout = (("${currentBuild.timeInMillis}".substring(0, 10) as Integer) + 3600)
            timeUrl = "$env.QUAY_API"+service+"/build/?since="+timestamp
            timeQuery = "curl -s "+timeUrl+/ | jq '.builds[] | "\(.tags[]),\(.display_name),\(.phase)"'/
            limitUrl = "$env.QUAY_API"+service+"/build/?limit=25"
            limitQuery = "curl -s "+limitUrl+/ | jq '.builds[] | "\(.tags[]),\(.display_name),\(.phase)"'/
            
            def quayImageReady = false
            def noPendingQuayBuilds = false
            while(quayImageReady != true && noPendingQuayBuilds != true) {
              noPendingQuayBuilds = true
              currentTime = new Date().getTime()/1000 as Integer
              println "currentTime is: "+currentTime
  
              if(currentTime > timeout) {
                currentBuild.result = 'ABORTED'
                error("aborting build due to timeout")
              }
  
              sleep(30)
              println "running time query"
              resList = sh(script: timeQuery, returnStdout: true).trim().split('"\n"')
              for (String res in resList) {
                fields = res.replaceAll('"', "").split(',')
  
                //
                // if all quay builds are complete, then assume there's nothing to wait
                // for even if a build for our commit is not pending.
                // that can happen if someone re-runs a Jenkins job interactively or whatever
                //
                if (fields.length > 2) {
                  noPendingQuayBuilds = noPendingQuayBuilds && fields[2].endsWith("complete")
                  if(fields[0].startsWith("$env.GIT_BRANCH".replaceAll("/", "_"))) {
                    if("$env.GIT_COMMIT".startsWith(fields[1])) {
                      quayImageReady = fields[2].endsWith("complete")
                      break
                    } else {
                      currentBuild.result = 'ABORTED'
                      error("aborting build due to out of date git hash\npipeline: $env.GIT_COMMIT\nquay: "+fields[1])
                    }
                  }
                }
              }

              if (!quayImageReady) {
                println "time query failed, running limit query"
                resList = sh(script: limitQuery, returnStdout: true).trim().split('"\n"')
                for (String res in resList) {
                  fields = res.replaceAll('"', "").split(',')
                  //
                  // if all quay builds are complete, then assume there's nothing to wait
                  // for even if a build for our commit is not pending.
                  // that can happen if someone re-runs a Jenkins job interactively or whatever
                  //
                  if (fields.length > 2) {
                    noPendingQuayBuilds = noPendingQuayBuilds && fields[2].endsWith("complete")
                    
                    if(fields[0].startsWith("$env.GIT_BRANCH".replaceAll("/", "_"))) {
                      if("$env.GIT_COMMIT".startsWith(fields[1])) {
                        quayImageReady = fields[2].endsWith("complete")
                        break
                      } else {
                        currentBuild.result = 'ABORTED'
                        error("aborting build due to out of date git hash\npipeline: $env.GIT_COMMIT\nquay: "+fields[1])
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
      stage('SelectNamespace') {
        steps {
          script {
            println config.toMapString(1000)
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
          klockNamespace( mehod: 'unlock', uid: uid )
        }
        echo "done"
        junit "gen3-qa/output/*.xml"
      }
    }
  }
}