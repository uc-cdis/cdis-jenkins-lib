import groovy.json.JsonSlurperClassic

def httpApiRequest(String apiContext, String urlPath) {
  try{
    def PR_NUMBER = env.BRANCH_NAME.split('-')[1];
    def REPO_NAME = env.JOB_NAME.split('/')[1];
    println('REPO_NAME: ' + REPO_NAME);
    pr_url="https://api.github.com/repos/uc-cdis/${REPO_NAME}/${apiContext}/${PR_NUMBER}${urlPath}"
    println("Shooting a request to: " + pr_url);
    def get = new URL(pr_url).openConnection();
    def getRC = get.getResponseCode();
    println(getRC);
    def jsonSlurper = new groovy.json.JsonSlurperClassic();
    prMetadata = null;
    if(getRC.equals(200)) {
      prMetadataJson = get.getInputStream().getText();
      //println(prMetadataJson);
      prMetadata = jsonSlurper.parseText(prMetadataJson);
    }
    return prMetadata;
  } catch (e) {
    pipelineHelper.handleError(e)
  }
  return null;  
}

def fetchRepoURL() {
  def prMetadata = httpApiRequest("pulls", "")
  return prMetadata['head']['repo']['url'];
}

def fetchLabels() {
  def prMetadata = httpApiRequest("issues", "/labels")
  println("### ## Labels: ${prMetadata}")
  return prMetadata;
}

def isDraft() {
  def prMetadata = httpApiRequest("pulls", "")
  return prMetadata['draft'];
}
