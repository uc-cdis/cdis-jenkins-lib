
def setCloudAutomationPath(String path) {
  cloudAutoDir = new File(path)
  assert cloudAutoDir.exists() : "Provided path for cloud-automation does not exist: "+cloudAutomationPath
  if (path.endsWith("/")) {
    path = path.substring(0, path.length() - 1);
  }
  cloudAutomationPath = path
}

def setKubectlNamespace(String name) {
  kubectlNamespace = name
}

def assertKubeReady() {
  assert this.hasProperty('kubectlNamespace') : "kubectlNamespace property for kubeHelper is not set."
  assert this.hasProperty('cloudAutomationPath') : "cloudAutomationPath property for kubeHelper is not set."
}

def kube(Closure body) {
  assertKubeReady()
  withEnv(['GEN3_NOPROXY=true', "vpc_name=${kubectlNamespace}", "GEN3_HOME=${cloudAutomationPath}", "KUBECTL_NAMESPACE=${kubectlNamespace}"]) {
    body()
  }
}

def klock(Map params) {
  kube {
    return sh( script: "bash ${cloudAutomationPath}/gen3/bin/klock.sh ${params.method} jenkins ${params.uid} 3600 -w 60", returnStatus: true)
  }
}

def deploy() {
  kube {
    echo "GEN3_HOME is $env.GEN3_HOME"
    echo "GIT_BRANCH is ${env.GIT_BRANCH}"
    echo "GIT_COMMIT is ${env.GIT_COMMIT}"
    echo "KUBECTL_NAMESPACE is ${kubectlNamespace}"
    echo "WORKSPACE is $env.WORKSPACE"
    sh "bash ${cloudAutomationPath}/gen3/bin/kube-roll-all.sh"
    sh "bash ${cloudAutomationPath}/gen3/bin/kube-wait4-pods.sh || true"
  }
}

// if manifest directory not set, tries to use default
def editServiceBranch(String serviceName, String branchName=env.GIT_BRANCH, String manifestPath="cdis-manifest") {
  if (null == serviceName) {
    throw new IllegalStateException("must specify service");    
  }
  if (null == branchName) {
    throw new IllegalArgumentException("unable to determine branch name");
  }
  if (null == serviceName) {
    throw new IllegalArgumentException("must specify serviceName");
  }
  kube {
    namespaceDir = sh(script: "kubectl -n ${kubectlNamespace} get configmap global -o jsonpath='{.data.hostname}'", returnStdout: true)
    dir("${manifestPath}/${namespaceDir}") {
      quaySuffix = quayHelper.formatBranchToQuayName(branchName)
      currentBranch = "${serviceName}:[a-zA-Z0-9._-]*"
      targetBranch = "${serviceName}:${quaySuffix}"
      // swap current branch for the target branch
      sh 'sed -i -e "s,'+"${currentBranch},${targetBranch}"+',g" manifest.json'
    }
  }
}