import groovy.transform.Field

@Field def config // pipeline config shared between helpers
@Field def requiredProps // properties required for polling quay

/**
* Constructor
*/
def create(Map config) {
  this.config = config
  this.requiredProps = ['service', 'GIT_COMMIT', 'branchFormatted']

  return this
}

def getMissingProperties() {
  missingProps = []
  this.requiredProps.each {
    echo this.config[it]
    if (!this.config.containsKey(it)) {
      missingProps << "${it}"
    }
  }
}

def waitForBuild() {
  // echo this.config
  missingProps = getMissingProperties()
  if (missingProps.size() > 0) {
    error("Config is missing one or more properties required for checking Quay:\n  ${missingProps}")
  }
  service = this.config.service
  if (service == 'cdis-jenkins-lib') {
    service = 'jenkins-lib'
  }

  echo("Waiting for ${service} to build:\n  branch '${this.config.branchFormatted}'\n  commit ${this.config.GIT_COMMIT}")

  def timestamp = (("${currentBuild.timeInMillis}".substring(0, 10) as Integer) - 60)
  def timeout = (("${currentBuild.timeInMillis}".substring(0, 10) as Integer) + 3600)
  QUAY_API = 'https://quay.io/api/v1/repository/cdis/'
  timeUrl = "$QUAY_API"+service+"/build/?since="+timestamp
  timeQuery = "curl -s "+timeUrl+/ | jq '.builds[] | "\(.tags[]),\(.display_name),\(.phase)"'/
  limitUrl = "$QUAY_API"+service+"/build/?limit=25"
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
        if(fields[0].startsWith(this.config.branchFormatted)) {
          if(this.config.GIT_COMMIT.startsWith(fields[1])) {
            quayImageReady = fields[2].endsWith("complete")
            break
          } else {
            currentBuild.result = 'ABORTED'
            error("aborting build due to out of date git hash\npipeline: ${this.config.GIT_COMMIT}\nquay: "+fields[1])
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
        // if all quay builds are complete, then assume there's nothing to wait
        // for even if a build for our commit is not pending.
        // that can happen if someone re-runs a Jenkins job interactively or whatever
        //
        if (fields.length > 2) {
          noPendingQuayBuilds = noPendingQuayBuilds && fields[2].endsWith("complete")
          
          if(fields[0].startsWith("$env.GIT_BRANCH".replaceAll("/", "_"))) {
            if("$env.GIT_COMMIT".startsWith(fields[1])) {
              quayImageReady = fields[2].endsWith("complete")
              break
            } else {
              currentBuild.result = 'ABORTED'
              error("aborting build due to out of date git hash\npipeline: $env.GIT_COMMIT\nquay: "+fields[1])
            }
          }
        }
      }
    }
  }
}