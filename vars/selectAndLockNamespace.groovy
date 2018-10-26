#!groovy

def call(String[] namespaces) {
  script {
    int randNum = new Random().nextInt(namespaces.length);
    uid = env.service+"-"+"$env.GIT_BRANCH".replaceAll("/", "_")+"-"+env.BUILD_NUMBER
    int lockStatus = 1;

    // try to find an unlocked namespace
    for (int i=0; i < namespaces.length && lockStatus != 0; ++i) {
      randNum = (randNum + i) % namespaces.length;
      env.KUBECTL_NAMESPACE = namespaces[randNum]
      println "selected namespace $env.KUBECTL_NAMESPACE on executor $env.EXECUTOR_NUMBER"
      println "attempting to lock namespace $env.KUBECTL_NAMESPACE with a wait time of 1 minutes"
      withEnv(['GEN3_NOPROXY=true', "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {
        lockStatus = klockNamespace( method: 'lock', uid: uid )
      }
    }
    if (lockStatus != 0) {
      error("aborting - no available workspace")
    }
  }
}
