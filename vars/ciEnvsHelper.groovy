def fetchCIEnvs(runOnGen3CIWorker = false) {
  try{
    def jenkins_envs_url="https://cdistest-public-test-bucket.s3.amazonaws.com/jenkins-envs-releases.txt";
    if (pipeConfig.MANIFEST == null || pipeConfig.MANIFEST == false || pipeConfig.MANIFEST != "True") {
      jenkins_envs_url="https://cdistest-public-test-bucket.s3.amazonaws.com/jenkins-envs-services.txt";
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
