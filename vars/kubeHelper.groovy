def cloudAutomationPath = "${env.WORKSPACE}/cloud-automation"

/**
* Runs kubectl commands
* Creates a context of environment variables required for commons commands
*
* @param body - instructions to execute
* @returns bodyResult
*/
def kube(String kubectlNamespace, Closure body) {
  vpc_name = "qaplanetv1"
  withEnv(['GEN3_NOPROXY=true', "vpc_name=${vpc_name}", "GEN3_HOME=${cloudAutomationPath}", "KUBECTL_NAMESPACE=${kubectlNamespace}"]) {
    echo "GEN3_HOME is $env.GEN3_HOME"
    echo "CHANGE_BRANCH is $env.CHANGE_BRANCH"
    echo "GIT_COMMIT is $env.GIT_COMMIT"
    echo "KUBECTL_NAMESPACE is $env.KUBECTL_NAMESPACE"
    echo "WORKSPACE is $env.WORKSPACE"
    echo "vpc_name is $env.vpc_name"
    return body()
  }
}

/**
* Attempts to lock kubectlNamespace
*
* @param method - lock or unlock
* @param owner - owner to lock as; defaults to conf.UID
* @returns klockResult - boolean True=success, False=failed
*/
def klock(String method, String owner, String lockName, String kubectlNamespace) {
  if (null == method || null == owner || null == lockName) {
    error("Missing a required parameter:\n  method: ${method}\n  owner: ${owner}\n  lockName: ${lockName}")
  }
  conditionalLockParams = ""
  if (method == "lock") {
    conditionalLockParams = "3600 -w 60"
  }
  kube(kubectlNamespace, {
    klockResult = sh( script: "bash ${cloudAutomationPath}/gen3/bin/klock.sh ${method} ${lockName} ${owner} ${conditionalLockParams}", returnStatus: true)
    if (klockSuccess == 0) {
      return True
    } else {
      return False
    }
  })
}

/**
* Rolls all pods for kubectlNamespace
*/
def deploy(String kubectlNamespace) {
  kube(kubectlNamespace, {
    sh "bash ${cloudAutomationPath}/gen3/bin/kube-roll-all.sh"
    sh "bash ${cloudAutomationPath}/gen3/bin/kube-wait4-pods.sh || true"
  })
}

/**
* Reset kubernetes namespace gen3 objects/services
* Note the reset script is internally acquiring a lock that we should keep track of
*/
def reset(String kubectlNamespace) {
  kube(kubectlNamespace, {
    sh "yes | bash ${cloudAutomationPath}/gen3/bin/reset.sh"
    sh "bash ${cloudAutomationPath}/gen3/bin/kube-setup-spark.sh"
  })
}

/**
* Wait for all pods to roll and check health
*/
def waitForPods(String kubectlNamespace) {
  kube(kubectlNamespace, {
    sh "bash ${cloudAutomationPath}/gen3/bin/kube-wait4-pods.sh"
  })
}

/**
* Struct for storing locks
* see http://pleac.sourceforge.net/pleac_groovy/classesetc.html "Using Classes as Structs"
*/
class KubeLock { String kubectlNamespace; String owner; String lockName }

/**
* Attempts to lock a namespace
* If it fails to lock a namespace, it raises an error, terminating the pipeline
*
* @param owner - lock owner
*/
def selectAndLockNamespace(String lockOwner) {
  namespaces = ['jenkins-dcp', 'jenkins-niaid', 'jenkins-brain', 'jenkins-genomel']
  lockName = 'jenkins'
  int randNum = new Random().nextInt(namespaces.size());

  // try to find an unlocked namespace
  for (int i=0; i < namespaces.size(); ++i) {
    randNum = (randNum + i) % namespaces.size();
    kubectlNamespace = namespaces.get(randNum)
    println("attempting to lock namespace ${kubectlNamespace} with a wait time of 1 minutes")
    if (klock('lock', lockOwner, lockName, kubectlNamespace)) {
      // return successful lock
      return [kubectlNamespace, new KubeLock('kubectlNamespace', lockOwner, lockName)]
    }
  }
  // unable to lock a namespace
  error("aborting - no available workspace")
}

/**
* Returns hostname of the current namespace
*/
def getHostname(String kubectlNamespace) {
  kube(kubectlNamespace, {
    return sh(script: "kubectl -n $env.KUBECTL_NAMESPACE get configmap global -o jsonpath='{.data.hostname}'", returnStdout: true)
  })
}

def teardown(List kubeLocks) {
  kubeLocks.each {
    klock('unlock', it.owner, it.lockName, it.kubectlNamespace)
  }
}