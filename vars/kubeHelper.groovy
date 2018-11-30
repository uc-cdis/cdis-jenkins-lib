def create(Map config) {
  this.conf = config
  this.cloudAutomationPath = "${env.WORKSPACE}/cloud-automation"
  this.kubectlNamespace = "NO_PIPELINE_NAMESPACE_SELECTED"
  echo "Kube after setup: ${this}, conf: ${this.conf}"
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
  cloudAutomationPath = path
}

// def setKubectlNamespace(String name) {
//   kubectlNamespace = name
// }

def assertKubeReady() {
  if (!kubectlNamespace) {
    if (env.KUBECTL_NAMESPACE) {
      kubectlNamespace = env.KUBECTL_NAMESPACE
    } else {
      error('unable to determine kubectlNamespace')
    }
  }
  if (!cloudAutomationPath) {
    setCloudAutomationPath("${env.WORKSPACE}/cloud-automation")
  }
}

/**
* Used to run kubectl commands
* Verfies required properties are set and sets environment variables required for commons commands
*
* @param body - instructions to execute
* @returns bodyResult
*/
def kube(Closure body) {
  // assertKubeReady()
  withEnv(['GEN3_NOPROXY=true', "vpc_name=${kubectlNamespace}", "GEN3_HOME=${cloudAutomationPath}", "KUBECTL_NAMESPACE=${kubectlNamespace}"]) {
    return body()
  }
}

/**
* Attempts to lock kubectlNamespace
*
* @param method - lock or unlock
* 
* @returns klockResult
*/
def klock(String method, String owner) {
  if (null == method) {
    error("locking method must be provided - lock or unlock")
  }
  if (null == owner) {
    owner = conf.UID
  }
  kube {
    return sh( script: "bash ${cloudAutomationPath}/gen3/bin/klock.sh ${method} jenkins ${owner} 3600 -w 60", returnStatus: true)
  }
}

/**
* Rolls all pods for kubectlNamespace
*/
def deploy() {
  kube {
    echo "GEN3_HOME is ${env.GEN3_HOME}"
    echo "GIT_BRANCH is ${env.GIT_BRANCH}"
    echo "GIT_COMMIT is ${env.GIT_COMMIT}"
    echo "KUBECTL_NAMESPACE is ${env.KUBECTL_NAMESPACE}"
    echo "WORKSPACE is ${env.WORKSPACE}"
    sh "bash ${cloudAutomationPath}/gen3/bin/kube-roll-all.sh"
    sh "bash ${cloudAutomationPath}/gen3/bin/kube-wait4-pods.sh || true"
  }
}

/**
* Edits manifest of a service to provided branch
* If no branch name provided, use GIT_BRANCH from environment
*
* @param serviceName
* @param quayBranchName
* @param manifest - path to root directory of manifests; defaults to cdis-manifest
*/
def editManifest(String serviceName, String quayBranchName=null, String manifestPath="cdis-manifest") {
  if (null == serviceName) {
    error("must specify service");
  }
  if (null == branchName) {
    branchName = conf.BRANCH_FORMATTED
  }

  kube {
    namespaceDir = sh(script: "kubectl -n ${kubectlNamespace} get configmap global -o jsonpath='{.data.hostname}'", returnStdout: true)
    dir("${manifestPath}/${namespaceDir}") {
      quaySuffix = serviceHelper.formatBranchName(branchName)
      currentBranch = "${serviceName}:[a-zA-Z0-9._-]*"
      targetBranch = "${serviceName}:${quaySuffix}"
      // swap current branch for the target branch
      sh 'sed -i -e "s,'+"${currentBranch},${targetBranch}"+',g" manifest.json'
    }
  }
}

/**
* Attempts to lock a namespace
* If it fails to lock a namespace, it raises an error, terminating the pipeline
*
* @param namespaces - List of namespaces to select from randomly
*/
def selectAndLockNamespace(List<String> namespaces, String owner) {
  if (null == namespaces) {
    echo "Kube's conf ${this}, ${this.conf}, ${conf}"
    namespaces = conf.namespaces
  }
  int randNum = new Random().nextInt(namespaces.size());
  int lockStatus = 1;

  // try to find an unlocked namespace
  for (int i=0; i < namespaces.size() && lockStatus != 0; ++i) {
    randNum = (randNum + i) % namespaces.size();
    kubectlNamespace = namespaces.get(randNum)
    println "selected namespace ${kubectlNamespace} on executor ${env.EXECUTOR_NUMBER}"
    println "attempting to lock namespace ${kubectlNamespace} with a wait time of 1 minutes"
    lockStatus = this.klock('lock', owner)
  }
  if (lockStatus != 0) {
    error("aborting - no available workspace")
  }
}