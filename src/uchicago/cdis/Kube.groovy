#!/usr/bin/groovy
package uchicago.cdis;

Kube(pipelineDefinition) {
  def pd = pipelineDefinition
}

def deploy() {
  assert env.KUBECTL_NAMESPACE != "" : "Must define KUBECTL_NAMESPACE to deploy"
  withEnv(['GEN3_NOPROXY=true', "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {
    echo "GEN3_HOME is $env.GEN3_HOME"
    echo "GIT_BRANCH is $env.GIT_BRANCH"
    echo "GIT_COMMIT is $env.GIT_COMMIT"
    echo "KUBECTL_NAMESPACE is $env.KUBECTL_NAMESPACE"
    echo "WORKSPACE is $env.WORKSPACE"
    sh "bash cloud-automation/gen3/bin/kube-roll-all.sh"
    sh "bash cloud-automation/gen3/bin/kube-wait4-pods.sh || true"
  }
}

return this