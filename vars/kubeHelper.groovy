
def setCloudAutomationPath(String path) {
  cloudAutoDir = new File(path)
  assert cloudAutoDir.exists() : "Provided path for cloud-automation does not exist: "+cloudAutomationPath
  if (path.endsWith("/")) {
    path = path.substring(0, path.length() - 1);
  }
  cloudAutomationPath = path
}

def setKubeNamespace(String name) {
  kubectlNamespace = name
}

def assertKubeReady() {
  assert this.hasProperty(kubectlNamespace) : "Kubectl Namespace not set"
  assert this.hasProperty(cloudAutomationPath) : "Path to cloud-automation directory not set"
}

def kube(Closure body) {
  assertKubeReady()
  withEnv(['GEN3_NOPROXY=true', "vpc_name=${kubectlNamespace}", "GEN3_HOME=${cloudAutomationPath}"]) {
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
    // echo "GEN3_HOME is $env.GEN3_HOME"
    // echo "GIT_BRANCH is $env.GIT_BRANCH"
    // echo "GIT_COMMIT is $env.GIT_COMMIT"
    // echo "KUBECTL_NAMESPACE is $env.KUBECTL_NAMESPACE"
    // echo "WORKSPACE is $env.WORKSPACE"
    sh "bash ${cloudAutomationPath}/gen3/bin/kube-roll-all.sh"
    sh "bash ${cloudAutomationPath}/gen3/bin/kube-wait4-pods.sh || true"
  }
}