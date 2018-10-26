#!groovy

def call(config) {
  script {
    // def namespaces = config.namespaceChoices.replaceAll("\\s","").split(',')
    List<String> namespaces = config.namespaceChoices
    int randNum = new Random().nextInt(size(namespaces));
    uid = env.service+"-"+"$env.GIT_BRANCH".replaceAll("/", "_")+"-"+env.BUILD_NUMBER
    int lockStatus = 1;

    // try to find an unlocked namespace
    for (int i=0; i < size(namespaces) && lockStatus != 0; ++i) {
      randNum = (randNum + i) % size(namespaces);
      env.KUBECTL_NAMESPACE = namespaces.get(randNum)
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
