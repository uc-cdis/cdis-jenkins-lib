/**
* Waits for Quay to finish building the branch in config
*/
def waitForBuild(String repoName, String formattedBranch, String commit) {
  if (null == repoName || null == formattedBranch || null == commit) {
    error("Missing one or more properties required for checking Quay:\n  repoName: ${repoName}\n  formattedBranch: ${formattedBranch}\n  commit: ${commit}")
  }

  if (repoName == 'cdis-jenkins-lib') {
    repoName = 'jenkins-lib'
  }

  echo("Waiting for Quay to build:\n  repoName: ${repoName}\n  branch: '${formattedBranch}'\n  commit: ${commit}")

  def timestamp = (("${currentBuild.timeInMillis}".substring(0, 10) as Integer) - 60)
  def timeout = (("${currentBuild.timeInMillis}".substring(0, 10) as Integer) + 3600)
  QUAY_API = 'https://quay.io/api/v1/repository/cdis/'
  timeUrl = "$QUAY_API"+repoName+"/build/?since="+timestamp
  timeQuery = "curl -s "+timeUrl+/ | jq '.builds[] | "\(.tags[]),\(.display_name),\(.phase)"'/
  limitUrl = "$QUAY_API"+repoName+"/build/?limit=25"
  limitQuery = "curl -s "+limitUrl+/ | jq '.builds[] | "\(.tags[]),\(.display_name),\(.phase)"'/
  
  def quayImageReady = false
  def noPendingQuayBuilds = false
  while(quayImageReady != true && noPendingQuayBuilds != true) {
    noPendingQuayBuilds = true
    currentTime = new Date().getTime()/1000 as Integer
    println "currentTime is: "+currentTime

    if(currentTime > timeout) {
      currentBuild.result = 'ABORTED'
      error("aborting build due to timeout")
    }

    sleep(30)
    println "running time query"
    resList = sh(script: timeQuery, returnStdout: true).trim().split('"\n"')
    for (String res in resList) {
      fields = res.replaceAll('"', "").split(',')

      //
      // if all quay builds are complete, then assume there's nothing to wait
      // for even if a build for our commit is not pending.
      // that can happen if someone re-runs a Jenkins job interactively or whatever
      //
      if (fields.length > 2) {
        noPendingQuayBuilds = noPendingQuayBuilds && fields[2].endsWith("complete")
        if(fields[0].startsWith(formattedBranch)) {
          if(commit.startsWith(fields[1])) {
            quayImageReady = fields[2].endsWith("complete")
            if (quayImageReady) {
              println "found quay build: "+res
            }
            break
          } else if(env.GIT_PREVIOUS_COMMIT && env.GIT_PREVIOUS_COMMIT.startsWith(fields[1])) {
            // previous commit is the newest - sleep and try again
            // things get annoying when quay gets slow
            break
          } else {
            currentBuild.result = 'ABORTED'
            error("aborting build due to out of date git hash\ntag: ${formattedBranch}\npipeline: ${commit}\nquay: "+fields[1])
          }
        }
      }
    }

    if (!quayImageReady) {
      println "time query failed, running limit query"
      resList = sh(script: limitQuery, returnStdout: true).trim().split('"\n"')
      for (String res in resList) {
        fields = res.replaceAll('"', "").split(',')
        //
        // this loop assumes quay gives us back builds in reverse timestamp order.
        // if all quay builds are complete, then assume there's nothing to wait
        // for even if a build for our commit is not pending.
        // that can happen if someone re-runs a Jenkins job interactively or whatever
        //
        if (fields.length > 2) {
          noPendingQuayBuilds = noPendingQuayBuilds && fields[2].endsWith("complete")
          
          if(fields[0].startsWith(formattedBranch)) {
            if(commit.startsWith(fields[1])) {
              quayImageReady = fields[2].endsWith("complete")
              if (quayImageReady) {
                println "found quay build: "+res
              }
              break
            } else if(env.GIT_PREVIOUS_COMMIT && env.GIT_PREVIOUS_COMMIT.startsWith(fields[1])) {
              // previous commit is the newest - sleep and try again
              // things get annoying when quay gets slow
              break
            } else {
              // if previous commit is the newest one in quay, then maybe
              // the job's commit hasn't appeared yet. 
              // otherwise assume some other newer commit is in the process of building in quay
              currentBuild.result = 'ABORTED'
              error("aborting build due to out of date git hash\ntag: ${formattedBranch}\npipeline: ${commit}\nquay: "+fields[1])
            }
          }
        }
      }
    }
  }
}