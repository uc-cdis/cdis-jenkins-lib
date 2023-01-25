def fetchCIEnvs(pool = "service") {
  println("----------------------- FETCHCIENVS.POOL ---------------------")
  println("Pool value is ${pool}")
  println("---------------------------------------------------------------")
  try{
    def jenkins_envs_url;
    if(pool == "service") {
      jenkins_envs_url="https://cdistest-public-test-bucket.s3.amazonaws.com/jenkins-envs-services.txt";
    } else if(pool == "release") {
      jenkins_envs_url="https://cdistest-public-test-bucket.s3.amazonaws.com/jenkins-envs-releases.txt";
    }
    println("----------------------- JENKINS_ENVS_URL ---------------------")
    println(jenkins_envs_url)
    println("---------------------------------------------------------------")
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
