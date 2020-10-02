def cloudAutomationPath() {
  def sanitized_workspace = env.WORKSPACE.replaceAll(" ", "\\ ");
  return "${sanitized_workspace}/cloud-automation"
}

/**
* Runs kubectl commands
* Creates a context of environment variables required for commons commands
*
* @param body - instructions to execute
* @returns bodyResult
*/
def kube(String kubectlNamespace, Closure body) {
  def vpc_name = sh(script: "kubectl get cm --namespace ${kubectlNamespace} global -o jsonpath=\"{.data.environment}\"", returnStdout: true);
  withEnv(['GEN3_NOPROXY=true', "vpc_name=${vpc_name}", "GEN3_HOME=${cloudAutomationPath()}", "KUBECTL_NAMESPACE=${kubectlNamespace}"]) {
    echo "GEN3_HOME is $env.GEN3_HOME"
    echo "BRANCH_NAME is $env.BRANCH_NAME"
    echo "CHANGE_BRANCH is $env.CHANGE_BRANCH"
    echo "GIT_COMMIT is $env.GIT_COMMIT"
    echo "KUBECTL_NAMESPACE is $env.KUBECTL_NAMESPACE"
    echo "WORKSPACE is $env.WORKSPACE"
    if (env.WORKSPACE.indexOf("\\") == -1) {
        env.WORKSPACE = env.WORKSPACE.replaceAll(" ", "\\\\ ");
        echo "sanitized WORKSPACE is $env.WORKSPACE"
    }
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
    conditionalLockParams = "7200 -w 60"
  }
  kube(kubectlNamespace, {
    klockResult = sh( script: "bash ${cloudAutomationPath()}/gen3/bin/klock.sh ${method} '${lockName}' '${owner}' ${conditionalLockParams}", returnStatus: true)
    if (klockResult == 0) {
      return true
    } else {
      return false
    }
  })
}

/**
* Rolls all pods for kubectlNamespace
*/
def deploy(String kubectlNamespace) {
  kube(kubectlNamespace, {
    sh "bash ${cloudAutomationPath()}/gen3/bin/kube-roll-all.sh"
    sh "bash ${cloudAutomationPath()}/gen3/bin/kube-wait4-pods.sh || true"
  })
}

/**
* Reset kubernetes namespace gen3 objects/services
* Note the reset script is internally acquiring a lock that we should keep track of
*/
def reset(String kubectlNamespace) {
  kube(kubectlNamespace, {
    sh "yes | bash ${cloudAutomationPath()}/gen3/bin/reset.sh"
    sh "bash ${cloudAutomationPath()}/gen3/bin/kube-setup-spark.sh || true"
  })
}

/**
* Wait for all pods to roll and check health
*/
def waitForPods(String kubectlNamespace) {
  kube(kubectlNamespace, {
    sh "bash ${cloudAutomationPath()}/gen3/bin/kube-wait4-pods.sh"
  })
}

/**
* Map for storing locks
*/
def newKubeLock(String kubectlNamespace, String lockOwner, String lockName) {
  return [kubectlNamespace: kubectlNamespace, lockOwner: lockOwner, lockName: lockName]
}

/**
* Attempts to lock a namespace
* If it fails to lock a namespace, it raises an error, terminating the pipeline
*
* @param owner - lock owner
*/
def selectAndLockNamespace(String lockOwner, List<String> namespaces = null) {
  lockName = 'jenkins'
  int randNum = new Random().nextInt(namespaces.size());

  times = 0

  while(times != 120) {
    // try to find an unlocked namespace
    for (int i=0; i < namespaces.size(); ++i) {
      namespaceIndex = (randNum + i) % namespaces.size();
      kubectlNamespace = namespaces.get(namespaceIndex)
      println("attempting to lock namespace ${kubectlNamespace} with a wait time of 1 minutes")
      if (klock('lock', lockOwner, lockName, kubectlNamespace)) {
        echo("namespace ${kubectlNamespace}")
        return [kubectlNamespace, newKubeLock(kubectlNamespace, lockOwner, lockName)]
      } else {
        // unable to lock a namespace
        echo("no available workspace, yet...")
      }
    }
    times += 1
    sleep(60)
  }
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
    klock('unlock', it.lockOwner, it.lockName, it.kubectlNamespace)
  }
}
