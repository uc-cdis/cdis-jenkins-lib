#!groovy

def call(Map params) {
  script {
    List<String> namespaces = params.namespaces
    int randNum = new Random().nextInt(namespaces.size());
    int lockStatus = 1;

    // try to find an unlocked namespace
    for (int i=0; i < namespaces.size() && lockStatus != 0; ++i) {
      randNum = (randNum + i) % namespaces.size();
      env.KUBECTL_NAMESPACE = namespaces.get(randNum)
      println "selected namespace $env.KUBECTL_NAMESPACE on executor $env.EXECUTOR_NUMBER"
      println "attempting to lock namespace $env.KUBECTL_NAMESPACE with a wait time of 1 minutes"
      withEnv(['GEN3_NOPROXY=true', "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {
        lockStatus = klockNamespace( method: 'lock', uid: params.uid )
      }
    }
    if (lockStatus != 0) {
      error("aborting - no available workspace")
    }
  }
}
