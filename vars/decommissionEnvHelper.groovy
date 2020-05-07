def checkDecommissioningEnvironment() {
  HashMap fileChanges = gitHelper.getLatestChangeOfBranch('HEAD')
  nameOfEnvironment = fileChanges.keySet()[0]

  rawContentURL="https://raw.githubusercontent.com/uc-cdis/cdis-manifest/${env.BRANCH_NAME}/${nameOfEnvironment}/manifest.json"
  println("Shooting a request to: " + rawContentURL);
  def get = new URL(rawContentURL).openConnection();
  def getRC = get.getResponseCode();
  println(getRC);
  if(getRC.equals(404)) {
    println("Skip tests as this environment folder was not found in this PR");
    println("decommissioning environment, skipping tests...");
    return true;
  } else {
    println("The request that attempted to find the manifest did not return a 404.")
    println("Please check your PR changes.")
    return false
  }
}
