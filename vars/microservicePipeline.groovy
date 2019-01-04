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
            env.service = "$env.JOB_NAME".split('/')[1]
            env.quaySuffix = "$env.CHANGE_BRANCH".replaceAll("/", "_")
            fetchCode(config)
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
                env.quaySuffix = config.GIT_BRANCH.replaceAll("/", "_")
              }
            }
          }
        }
      }
      stage('WaitForQuayBuild') {
        steps {
          script {
            // ignore config service/branch overrides in WaitForQuayBuild ...
            service = "$env.JOB_NAME".split('/')[1]
            quaySuffix = "$env.CHANGE_BRANCH".replaceAll("/", "_")
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
                  if(fields[0].startsWith(quaySuffix)) {
                    if(env.GIT_COMMIT.startsWith(fields[1])) {
                      quayImageReady = fields[2].endsWith("complete")
                      if (quayImageReady) {
                        println "found quay build: "+res
                      }
                      break
                    } else if(env.GIT_PREVIOUS_COMMIT && env.GIT_PREVIOUS_COMMIT.startsWith(fields[1])) {
                      // previous commit is the newest - sleep and try again
                      // things get annoying when quay gets slow
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
                  // this loop assumes quay gives us back builds in reverse timestamp order.
                  // if all quay builds are complete, then assume there's nothing to wait
                  // for even if a build for our commit is not pending.
                  // that can happen if someone re-runs a Jenkins job interactively or whatever
                  //
                  if (fields.length > 2) {
                    noPendingQuayBuilds = noPendingQuayBuilds && fields[2].endsWith("complete")
                    
                    if(fields[0].startsWith(quaySuffix)) {
                      if(env.GIT_COMMIT.startsWith(fields[1])) {
                        quayImageReady = fields[2].endsWith("complete")
                        if (quayImageReady) {
                          println "found quay build: "+res
                        }
                        break
                      } else if(env.GIT_PREVIOUS_COMMIT && env.GIT_PREVIOUS_COMMIT.startsWith(fields[1])) {
                        // previous commit is the newest - sleep and try again
                        // things get annoying when quay gets slow
                        noPendingQuayBuilds = false
                        break
                      } else {
                        // if previous commit is the newest one in quay, then maybe
                        // the job's commit hasn't appeared yet. 
                        // otherwise assume some other newer commit is in the process of building in quay
                        currentBuild.result = 'ABORTED'
                        println("aborting build due to out of date git hash, tag: $quaySuffix, pipeline: $env.GIT_COMMIT, quay: "+fields[1])
                        error("aborting build due to out of date git hash\ntag: $quaySuffix\npipeline: $env.GIT_COMMIT\nquay: "+fields[1])
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
            String[] namespaces = ['jenkins-niaid']; //['jenkins-brain', 'jenkins-niaid', 'jenkins-dcp', 'jenkins-genomel']
            int nsIndex = 1; // test jenkins-niaid ... new Random().nextInt(namespaces.length);
            uid = env.service+"-"+env.quaySuffix+"-"+env.BUILD_NUMBER
            int lockStatus = 1;

            // try to find an unlocked namespace
            for (int i=0; i < namespaces.length && lockStatus != 0; ++i) {
              nsIndex = (nsIndex + i) % namespaces.length;
              env.KUBECTL_NAMESPACE = namespaces[nsIndex]
              println "selected namespace $env.KUBECTL_NAMESPACE on executor $env.EXECUTOR_NUMBER"
              println "attempting to lock namespace $env.KUBECTL_NAMESPACE with a wait time of 1 minutes"
              withEnv(['GEN3_NOPROXY=true', "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {
                lockStatus = sh( script: "bash cloud-automation/gen3/bin/klock.sh lock jenkins "+uid+" 3600 -w 60", returnStatus: true)
              }
            }
            if (lockStatus != 0) {
              error("aborting - no available workspace")
            }
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
              sh 'sed -i -e "s,'+"$env.masterBranch,$env.targetBranch"+',g" manifest.json && cat manifest.json'
            }
          }
        }
      }
      stage('DbResetK8sDeploy') {
        steps {
          withEnv(['GEN3_NOPROXY=true', "vpc_name=qaplanetv1", "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {
            echo "GEN3_HOME is $env.GEN3_HOME"
            echo "CHANGE_BRANCH is $env.CHANGE_BRANCH"
            echo "GIT_COMMIT is $env.GIT_COMMIT"
            echo "KUBECTL_NAMESPACE is $env.KUBECTL_NAMESPACE"
            echo "WORKSPACE is $env.WORKSPACE"
            sh "yes | bash cloud-automation/gen3/bin/reset.sh"
            sh "bash cloud-automation/gen3/bin/kube-setup-spark.sh"
          }
        }
      }
      stage('VerifyClusterHealth') {
        steps {
          withEnv(['GEN3_NOPROXY=true', "vpc_name=qaplanetv1", "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {
            sh "bash cloud-automation/gen3/bin/kube-wait4-pods.sh"
            sh "bash ./gen3-qa/check-pod-health.sh"
          }
        }
      }
      stage('GenerateTestData') {
        // Run the data simulator
        steps {
          dir('gen3-qa') {
            withEnv(['GEN3_NOPROXY=true', "vpc_name=qaplanetv1", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=$env.WORKSPACE/testData/"]) {
              sh "bash ./jenkins-simulate-data.sh $env.KUBECTL_NAMESPACE"
            }
          }
        }
      }
      stage('RunTests') {
        steps {
          dir('gen3-qa') {
            withEnv(['GEN3_NOPROXY=true', "vpc_name=qaplanetv1", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=$env.WORKSPACE/testData/"]) {
              sh "bash ./run-tests.sh $env.KUBECTL_NAMESPACE --service=$env.service"
            }
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
          uid = env.service+"-"+env.quaySuffix+"-"+env.BUILD_NUMBER
          withEnv(['GEN3_NOPROXY=true', "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {         
            sh("bash cloud-automation/gen3/bin/klock.sh unlock jenkins " + uid + " || true")
            sh("bash cloud-automation/gen3/bin/klock.sh unlock reset-lock gen3-reset || true")
          }
        }
        echo "done"
        junit "gen3-qa/output/*.xml"
      }
    }
  }
}
