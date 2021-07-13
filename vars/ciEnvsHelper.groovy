def fetchCIEnvs(pipeconfigManifest = false, theNode = 'master') {
  try{
    def jenkins_envs_url="https://cdistest-public-test-bucket.s3.amazonaws.com/jenkins-envs-services.txt";
    if (pipeconfigManifest == "True" || theNode == 'gen3-ci-worker') {
      jenkins_envs_url="https://cdistest-public-test-bucket.s3.amazonaws.com/jenkins-envs-releases.txt";
    }
    println("Shooting a request to: " + jenkins_envs_url);
    def get = new URL(jenkins_envs_url).openConnection();
    def getRC = get.getResponseCode();
    println(getRC);
    List<String> ciEnvironments = [];
    if(getRC.equals(200)) {
      ciEnvsRaw = get.getInputStream().getText();
      ciEnvironments = ciEnvsRaw.split('\n')
    }
    return ciEnvironments;
  } catch (e) {
    pipelineHelper.handleError(e)
  }
  return null;
}
