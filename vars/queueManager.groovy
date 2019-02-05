/*
* A script which kills redundant builds tracked in Git
*
* Iterates through each build and keeps track of the most recent build
* while killing older builds.
* Used by Jenkinsfile.queueManager for scheduled cleaning
*/
def cleanBuilds() {
  repos = Jenkins.instance.getItemByFullName("CDIS GitHub Org").getItems()
  mostRecentBuilds = [:]
  for (repo in repos) {
    for (branch in repo.getItems()) {
      repoBranch = "$repo.name-$branch.name"
      for (build in branch.builds) {
        if (build.building) {
          thisBuildNum = build.getNumber()
          if (!mostRecentBuilds[repoBranch]) {
            mostRecentBuilds["$repoBranch"] = ['num': thisBuildNum, 'build': build]
          } else {
            currentBestBuildNum = mostRecentBuilds[repoBranch]['num']
            if (currentBestBuildNum > thisBuildNum) {
              // kill this build
              println("killing $repoBranch $thisBuildNum")
              build.doStop()
            } else {
              println("killing $repoBranch $currentBestBuildNum ")
              mostRecentBuilds[repoBranch]['build'].doStop()
              mostRecentBuilds[repoBranch]['num'] = thisBuildNum
              mostRecentBuilds[repoBranch]['build'] = build
            }
          }
        }
      }
    }
  }
  println("Current Builds:")
  println(mostRecentBuilds)
}
