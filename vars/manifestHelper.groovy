import groovy.transform.Field

@Field def config // pipeline config shared between helpers

/**
* Constructor
*
* @param config
*/
def create(Map config) {
  this.config = config

  return this
}

/**
* Edits manifest of a service to provided branch
* Makes the edit in the cdis-manifest directory
*
* @param commonsHostname - hostname of commons to edit (e.g. qa-bloodpac.planx-pla.net)
* @param serviceName - defaults to conf.service
* @param quayBranchName - defaults to branch's name formatted
*/
def editService(String commonsHostname, String serviceName, String quayBranchName) {
  dir("cdis-manifest/${commonsHostname}") {
    currentBranch = "${serviceName}:[a-zA-Z0-9._-]*"
    targetBranch = "${serviceName}:${quayBranchName}"
    // swap current branch for the target branch
    sh 'sed -i -e "s,'+"${currentBranch},${targetBranch}"+',g" manifest.json'
  }
}

def getAffectedManifests(String masterDir, String otherDir) {
  affectedFiles = []

  // get all paths to commons manifests
  def masterManifestFiles = findFiles(glob: "${masterDir}/*/manifest.json")
  for (int i = 0; i < masterManifestFiles.length; i++) {
    // check if other branch also has the manifest
    def otherManifestFile = masterManifestFiles[i].path.replaceAll(masterDir, otherDir)
    if (fileExists(otherManifestFile)) {
      // check if the manifest files are the same
      def cmpRes = sh( script: "cmp ${masterManifestFiles[i].path} ${otherManifestFile} || true", returnStdout: true )
      // if the comparison result is not empty then the files are different
      echo("CMPRES OUT: ${cmpRes}")
      if (cmpRes != '') {
        affectedFiles << otherManifestFile
      }
    }
  }

  return affectedFiles
}