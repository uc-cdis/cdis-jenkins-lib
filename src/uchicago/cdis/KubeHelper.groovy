package uchicago.cdis;

/**
 * Groovy helper for deploying services to k8s in Jenkins pipelines.
 *
 * @see https://jenkins.io/doc/book/pipeline/shared-libraries/
 */
class KubeHelper implements Serializable {
  def steps
  
  /**
   * Constructor
   *
   * @param steps injects hook to Jenkins Pipeline runtime
   */
  KubeHelper(steps) { this.steps = steps; }

  /**
   * Deploy the current env.GIT_BRANCH to the k8s
   * cluster that kubectl talks to by doing the following:
   * <ul>
   * <li> if the branch has not already been deployed
   *   <ul>
   *    <li> create a namespace for the branch </li>
   *   </ul>
   * <li> Reapply the service and deployment in the branch namespace
   *        with the docker image tagged with git's branch name - quay does not yet tag with commit-sha :-(
   * </ul>
   *
   * @param serviceName should work with 'kubectl get services/serviceName'
   * @param branchName defaults to env.GIT_BRANCH
   * @param commitSha defaults to env.GIT_COMMIT
   */
  def deployBranch(String serviceName, String branchName=steps.env.GIT_BRANCH, String commitSha=steps.env.GIT_COMMIT) {
    if (null == branchName) {
      throw new IllegalStateException("unable to determine branch");    
    }
    if (null == commitSha) {
      throw new IllegalArgumentException("must specify git commit sha");
    }
    if (null == serviceName) {
      throw new IllegalArgumentException("must specify serviceName");
    }
    
    // Fetch the default service and deployment into ./service.json and ./deployment.json
    String appLabel = steps.sh( script: "kubectl get services/$serviceName -ojson | tee service.json | jq -r .spec.selector.app", returnStdout: true);
    String nowStr = new java.util.Date().toString();
    steps.sh( script: "kubectl get deployments -l app='$appLabel' -ojson | jq '.items[0] | { apiVersion:.apiVersion, kind:.kind, spec:(.spec | .template.metadata.labels.date=\"$nowStr\"), metadata:{ name: .metadata.name }}' | tee deployment.json")
    
    String namespace = "branch-" + branchName.replaceAll("\\W+", "-");
    String dockerTag = branchName.replaceAll("/", "_").replaceAll("\\W+", "-");

    // First - check if the branch namespace already exists ...
    if (steps.sh( script: "kubectl get namespaces -ojsonpath='{ .items[?(@.metadata.name==\"$namespace\")].metadata.name }'", returnStdout: true).trim().isEmpty()) {
      // branch namespace does not yet exist - create it!
      steps.sh( script: "kubectl create namespace $namespace");
      // copy the secrets and configs into the namespace
      steps.sh( script: "kubectl get secrets,configmaps -ojson | jq '.items[].metadata.namespace=\"$namespace\"' | kubectl create -f -")
    }
    
    // update the deployment to point at the latest Docker image
    steps.sh( script: "cat deployment.json | jq '.spec.template.spec.containers[].image=(.spec.template.spec.containers[].image | gsub(\":.+\$\";\":$dockerTag\"))' | kubectl apply --namespace $namespace -f -");
    // update the service in the branch namespace
    steps.sh( script: "cat service.json | jq '.metadata={namespace:\"$namespace\",name:.metadata.name} | del(.status) | del(.spec.ports[].nodePort) | del(.spec.clusterIP)' | kubectl apply --namespace $namespace -f -");
    // All done!
  }
}
