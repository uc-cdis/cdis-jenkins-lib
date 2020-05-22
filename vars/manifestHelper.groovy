/**
* Edits manifest of a service to provided branch
* Makes the edit in the cdis-manifest directory
*
* @param commonsHostname - hostname of commons to edit (e.g. qa-bloodpac.planx-pla.net)
* @param serviceName - defaults to conf.service
* @param quayBranchName - defaults to branch's name formatted
*/
def editService(String commonsHostname, String serviceName, String quayBranchName) {
  if (null == commonsHostname || null == serviceName || null == quayBranchName) {
    error("Mising parameter for editing manifest service:\n  commonsHostname: ${commonsHostname}\n  serviceName: ${serviceName}\n  quayBranchName: ${quayBranchName}")
  }
  dir("cdis-manifest/${commonsHostname}") {
    currentBranch = "${serviceName}:[a-zA-Z0-9._-]*"
    targetBranch = "${serviceName}:${quayBranchName}"
    echo "Editing cdis-manifest/${commonsHostname} service ${serviceName} to branch ${quayBranchName}"
    // swap current branch for the target branch
    sh 'sed -i -e "s,'+"${currentBranch},${targetBranch}"+',g" manifest.json'
    sh 'cat manifest.json'
  }
}

/**
* TODO: ask Thanh to document this function
*/
def mergeManifest(String changedDir, String selectedNamespace) {
  String od = sh(returnStdout: true, script: "jq -r .global.dictionary_url < tmpGitClone/$changedDir/manifest.json").trim()
  String pa = sh(returnStdout: true, script: "jq -r .global.portal_app < tmpGitClone/$changedDir/manifest.json").trim()
  String sj = sh(returnStdout: true, script: "jq .sower < tmpGitClone/$changedDir/manifest.json").trim()
  String s = sh(returnStdout: true, script: "jq -r keys < cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json")
  println s
  def keys = new groovy.json.JsonSlurper().parseText(s)
  String dels = ""
  for (String k : keys) {
    if (sh(returnStdout: true, script: "jq -r '.$k' < tmpGitClone/$changedDir/manifest.json").trim() == 'null') {
      if (dels == "")
        dels = dels + "del(.$k)"
      else
        dels = dels + " | del(.$k)"
    }
  }
  if (dels != "") {
    sh(returnStdout: true, script: "old=\$(cat cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json) && echo \$old | jq -r \'${dels}\' > cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json && echo \$old | jq '.sower = []' > cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json")
  }
  sh(returnStdout: true, script: "bs=\$(jq -r .versions < tmpGitClone/$changedDir/manifest.json) "
          + "&& old=\$(cat cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json) "
          + """&& echo \$old | jq -r --arg od ${od} --arg pa ${pa} --argjson sj ${sj} --argjson vs \"\$bs\"""" 
          + / '(.global.dictionary_url) |=/ + "\$od" + / | (.global.portal_app) |=/ + "\$pa"
          + / | (.versions) |=/ + "\$vs" + / | (.sower) |=/ + . "\$sj" + /'/ + " > cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json")
  String rs = sh(returnStdout: true, script: "cat cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json")
  return rs
}

/**
* TODO: ask Thanh to document
*/
def overwriteConfigFolders(String changedDir, String selectedNamespace) {
    List<String> folders = sh(returnStdout: true, script: "ls tmpGitClone/$changedDir").split()
    if (folders.contains('portal')) {
      sh(script: "cp -rf tmpGitClone/$changedDir/portal cdis-manifest/${selectedNamespace}.planx-pla.net/")

      // Some commons display a user agreement quiz after logging in for the
      // first time. This quiz is too customizable to be included in the tests
      // at the moment. This removes the requiredCerts var from the config so
      // that NO quizzes will be displayed.
      config_location = "cdis-manifest/${selectedNamespace}.planx-pla.net/portal/gitops.json"
      if (fileExists(config_location)) {
        sh(script: "sed -i '/\"requiredCerts\":/d' ${config_location}")
      }
    }
    if (folders.contains('etlMapping.yaml')) {
      sh(script: "cp -rf tmpGitClone/$changedDir/etlMapping.yaml cdis-manifest/${selectedNamespace}.planx-pla.net/")
    }
  }

/**
* TODO: ask Thanh to document
* @returns name of the changed cdis-manifest directory (=environment which is being tested)
*/
def manifestDiff(String selectedNamespace) {
    HashMap fileChanges = gitHelper.getLatestChangeOfBranch('HEAD')
    for (String key : fileChanges.keySet())
    {
      if (key != '.' && key != '.githooks')
      {
        String rs = mergeManifest(key, selectedNamespace)
        echo "new manifest $rs"
        overwriteConfigFolders(key, selectedNamespace)
        return key
      }
    }
  }
