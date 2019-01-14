import groovy.transform.Field

@Field def config // pipeline config shared between helpers
@Field def cloudAutomationPath // path to directory of pulled cloud-automation
@Field def kubectlNamespace // namespace to run kube commands in
@Field def vpcName
@Field def obtainedLock // indicates if successfully locked a namespace

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
  this.vpcName = "qaplanetv1"
  this.obtainedLock = 1 // no lock obtained yet
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
  withEnv(['GEN3_NOPROXY=true', "vpc_name=${this.vpcName}", "GEN3_HOME=${this.cloudAutomationPath}", "KUBECTL_NAMESPACE=${this.kubectlNamespace}"]) {
    echo "  GEN3_HOME is ${env.GEN3_HOME}\n  KUBECTL_NAMESPACE is ${env.KUBECTL_NAMESPACE}"
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
def klock(String method, String owner=null, String lockName="jenkins") {
  if (null == owner) {
    owner = this.config.UID
  }
  conditionalLockParams = ""
  if (method == "lock") {
    conditionalLockParams = "3600 -w 60"
  }
  kube {
    return sh( script: "bash ${this.cloudAutomationPath}/gen3/bin/klock.sh ${method} ${lockName} ${owner} ${conditionalLockParams}", returnStatus: true)
  }
}

/**
* Rolls all pods for kubectlNamespace
*/
def deploy() {
  kube {
    sh "bash ${this.cloudAutomationPath}/gen3/bin/kube-roll-all.sh"
    sh "bash ${this.cloudAutomationPath}/gen3/bin/kube-wait4-pods.sh || true"
  }
}

/**
* Reset kubernetes namespace gen3 objects/services
*/
def reset() {
  kube {
    sh "yes | bash ${this.cloudAutomationPath}/gen3/bin/reset.sh"
    sh "bash ${this.cloudAutomationPath}/gen3/bin/kube-setup-spark.sh"
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
      namespaces = ['jenkins-dcp', 'jenkins-niaid', 'jenkins-brain', 'jenkins-genomel']
    }
  }
  int randNum = new Random().nextInt(namespaces.size());
  this.obtainedLock = 1;

  // try to find an unlocked namespace
  for (int i=0; i < namespaces.size() && this.obtainedLock != 0; ++i) {
    randNum = (randNum + i) % namespaces.size();
    kubectlNamespace = namespaces.get(randNum)
    println "attempting to lock namespace ${this.kubectlNamespace} with a wait time of 1 minutes"
    this.obtainedLock = this.klock('lock', owner)
  }
  if (this.obtainedLock != 0) {
    error("aborting - no available workspace")
  }
}

/**
* Returns hostname of the current namespace
*/
def getHostname() {
  kube {
    return sh(script: "kubectl -n $env.KUBECTL_NAMESPACE get configmap global -o jsonpath='{.data.hostname}'", returnStdout: true)
  }
}

def teardown() {
  if (this.obtainedLock == 0) {
    klock('unlock')
  }
  // unlock the reset lock
  klock('unlock', 'gen3-reset', 'reset-lock')
}