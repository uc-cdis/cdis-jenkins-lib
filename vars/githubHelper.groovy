import groovy.json.JsonSlurperClassic
def fetchLabels() {
  try{
    def PR_NUMBER = env.BRANCH_NAME.split('-')[1];
    def REPO_NAME = env.JOB_NAME.split('/')[1];
    println('REPO_NAME: ' + REPO_NAME);
    labels_url="https://api.github.com/repos/uc-cdis/${REPO_NAME}/issues/${PR_NUMBER}/labels"
    println("Shooting a request to: " + labels_url);
    def get = new URL(labels_url).openConnection();
    def getRC = get.getResponseCode();
    println(getRC);
    def jsonSlurper = new groovy.json.JsonSlurperClassic();
    labels = null;
    if(getRC.equals(200)) {
      labelsJson = get.getInputStream().getText();
      println(labelsJson);
      labels = jsonSlurper.parseText(labelsJson);
    }
    return labels;
  } catch (e) {
    pipelineHelper.handleError(e)
  }
  return null;
}