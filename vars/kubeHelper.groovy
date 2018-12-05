import groovy.transform.Field

@Field def config // pipeline config shared between helpers
@Field def cloudAutomationPath // path to directory of pulled cloud-automation
@Field def kubectlNamespace // namespace to run kube commands in

/**
* Constructor for kubeHelper
* Provides access to kubectl and gen3 commands
*
* @param config - pipeline config
*/
def create(Map config) {
  this.config = config
  this.cloudAutomationPath = "${env.WORKSPACE}/cloud-automation"
  this.kubectlNamespace = "NO_PIPELINE_NAMESPACE_SELECTED"
  return this
}

/**
* Sets path to the cloud automation directory, strips any trailing /'s
*
* @param path
*/
def setCloudAutomationPath(String path) {
  cloudAutoDir = new File(path)
  assert cloudAutoDir.exists() : "Provided path for cloud-automation does not exist: "+cloudAutomationPath
  if (path.endsWith("/")) {
    path = path.substring(0, path.length() - 1);
  }
  this.cloudAutomationPath = path
}

/**
* Runs kubectl commands
* Creates a context of environment variables required for commons commands
*
* @param body - instructions to execute
* @returns bodyResult
*/
def kube(Closure body) {
  withEnv(['GEN3_NOPROXY=true', "vpc_name=${this.kubectlNamespace}", "GEN3_HOME=${this.cloudAutomationPath}", "KUBECTL_NAMESPACE=${this.kubectlNamespace}"]) {
    return body()
  }
}

/**
* Attempts to lock kubectlNamespace
*
* @param method - lock or unlock
* @param owner - owner to lock as; defaults to conf.UID
* @returns klockResult
*/
def klock(String method, String owner=null) {
  if (null == owner) {
    owner = this.config.UID
  }
  conditionalLockParams = ""
  if (method == "lock") {
    conditionalLockParams = "3600 -w 60"
  }
  kube {
    return sh( script: "bash ${this.cloudAutomationPath}/gen3/bin/klock.sh ${method} jenkins ${owner} ${conditionalLockParams}", returnStatus: true)
  }
}

/**
* Rolls all pods for kubectlNamespace
*/
def deploy() {
  kube {
    echo "GEN3_HOME is ${env.GEN3_HOME}"
    echo "KUBECTL_NAMESPACE is ${env.KUBECTL_NAMESPACE}"
    sh "bash ${this.cloudAutomationPath}/gen3/bin/kube-roll-all.sh"
    sh "bash ${this.cloudAutomationPath}/gen3/bin/kube-wait4-pods.sh || true"
  }
}

/**
* Attempts to lock a namespace
* If it fails to lock a namespace, it raises an error, terminating the pipeline
*
* @param namespaces - List of namespaces to select from randomly; defaults to conf.namespaces
* @param owner - lock owner; defaults to null, to be handled by klock()
*/
def selectAndLockNamespace(List<String> namespaces=null, String owner=null) {
  if (null == namespaces) {
    if (this.config.containsKey('namespaces')) {
      namespaces = this.config.namespaces
    } else {
      namespaces = ['jenkins-dcp', 'jenkins-niaid', 'jenkins-brain']
    }
    
  }
  int randNum = new Random().nextInt(namespaces.size());
  int lockStatus = 1;

  // try to find an unlocked namespace
  for (int i=0; i < namespaces.size() && lockStatus != 0; ++i) {
    randNum = (randNum + i) % namespaces.size();
    kubectlNamespace = namespaces.get(randNum)
    println "attempting to lock namespace ${this.kubectlNamespace} with a wait time of 1 minutes"
    lockStatus = this.klock('lock', owner)
  }
  if (lockStatus != 0) {
    error("aborting - no available workspace")
  }
}

def getHostname() {
  kube {
    return sh(script: "kubectl -n $env.KUBECTL_NAMESPACE get configmap global -o jsonpath='{.data.hostname}'", returnStdout: true)
  }
}