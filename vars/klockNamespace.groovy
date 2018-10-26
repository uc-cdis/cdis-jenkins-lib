#!groovy

// Attempts to lock KUBECTL_NAMESPACE
def call(method, uid) {
  withEnv(['GEN3_NOPROXY=true', "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {
    return sh( script: "bash cloud-automation/gen3/bin/klock.sh "+method+" jenkins "+uid+" 3600 -w 60", returnStatus: true)
  }
}
