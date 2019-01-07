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
      stage('NormalBuild') {
        when {
          expression { config.MANIFEST == null || config.MANIFEST != "True" }
        }
        stages {
          stage('WaitForQuayBuild') {
            steps {
              script {
                waitForQuay()
              }
            }
          }
          stage('SelectNamespace') {
            steps {
              script {
                selectNamespace()
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
        }
      }
      stage('ManifestBuild') {
        when {
          expression { config.MANIFEST != null && config.MANIFEST == "True" }
        }
        stages {
          stage('SelectNamespace') {
            steps {
              script {
                selectNamespace()
              }
            }
          }
          // This steps will merge the cdis-manifest repository with the cdis-manifest folder (where script is checked out from gitops-qa) in jenkins pod.
          stage('ModifyManifest') {
            steps {
              script {
                echo "WORKSPACE is $env.WORKSPACE"
                manifestDiff()
              }
            }
          }
        }
      }
      stage('DbResetK8sDeploy') {
        when {
          expression { env.KUBECTL_NAMESPACE != null && env.KUBECTL_NAMESPACE != 'default'}
        }
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
      stage('FetchDataClient') {
        steps {
          dir('dataclient') {
            script {
              // we get the data client from master, unless the service being
              // tested is the data client itself, in which case we get the
              // executable for the current branch
              // Note: the data client does not use Jenkins yet (see PXP-2211)
              branch = "master"
              if (env.service == "cdis-data-client") {
                branch = env.CHANGE_BRANCH
                println "Testing cdis-data-client on branch " + branch
              }

              // Note: at this time, tests are always run on linux
              os = "linux"

              // download the gen3 data client executable from S3
              download_location = "dataclient.zip"
              sh String.format("aws s3 cp s3://cdis-dc-builds/%s/dataclient_%s.zip %s", branch, os, download_location)
              assert fileExists(download_location)
              unzip(download_location)

              // make sure we can execute it
              executable_name = "gen3-client"
              assert fileExists(executable_name)
              sh "mv $executable_name $env.WORKSPACE/$executable_name"
              sh "chmod u+x $env.WORKSPACE/$executable_name"
              sh "$env.WORKSPACE/$executable_name --version"

              println "Data client successfully set up at: $env.WORKSPACE/$executable_name"
            }
          }
        }
      }
      stage('RunTests') {
        steps {
          dir('gen3-qa') {
            withEnv(['GEN3_NOPROXY=true', "vpc_name=qaplanetv1", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "NAMESPACE=$env.KUBECTL_NAMESPACE", "TEST_DATA_PATH=$env.WORKSPACE/testData/",
            "DATA_CLIENT_PATH=$env.WORKSPACE"]) {
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
          if (env.CHANGE_ID) {
            // bla
            // see https://github.com/jenkinsci/pipeline-github-plugin#pullrequest
            pullRequest.comment('Hello from Atharva')
          }
        }
        echo "done"
        junit "gen3-qa/output/*.xml"
      }
    }
  }
}
