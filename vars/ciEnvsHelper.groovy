def fetchCIEnvs(pool = "service") {
  println "#### the pool is ${pool}"
  try{
    def jenkins_envs_url;
    if(pool.equals("service")) {
      jenkins_envs_url="https://cdistest-public-test-bucket.s3.amazonaws.com/jenkins-envs-services.txt";
      println "In service pool";
      println(jenkins_envs_url);
    } else if(pool.equals("release")) {
      jenkins_envs_url="https://cdistest-public-test-bucket.s3.amazonaws.com/jenkins-envs-releases.txt";
      println "In release pool";
      println(jenkins_envs_url);
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
