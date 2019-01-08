#!groovy

def call(String[] namespaces = null) {
    // by default run on one of the following 4 environments
    if (namespaces == null) {
        namespaces = ["jenkins-brain", "jenkins-niaid", "jenkins-dcp", "jenkins-genomel"]
    }
    
    int randNum = new Random().nextInt(namespaces.length);
    uid = "$env.service-$env.quaySuffix-$env.BUILD_NUMBER"
    int lockStatus = 1;

    // try to find an unlocked namespace
    for (int i=0; i < namespaces.length && lockStatus != 0; ++i) {
        randNum = (randNum + i) % namespaces.length;
        env.KUBECTL_NAMESPACE = namespaces[randNum]
        println "selected namespace $env.KUBECTL_NAMESPACE on executor $env.EXECUTOR_NUMBER"
        println "attempting to lock namespace $env.KUBECTL_NAMESPACE with a wait time of 1 minutes"
        withEnv(["GEN3_NOPROXY=true", "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {
            lockStatus = sh(script: "bash cloud-automation/gen3/bin/klock.sh lock jenkins $uid 3600 -w 60", returnStatus: true)
        }
    }
    if (lockStatus != 0) {
        error("aborting - no available workspace")
    }
}
