/**
* Jenkins environments are used for continuous integration, therefore, they should
* always run the latest-latest code for all components
* (that is why all Jenkins CI environments, by default, have every service set to the "master" version).
* Except in two cases:
*   - When the PR belongs to a service-specific repo, the service that is being tested
*     must have its version modified in the manifest to deploy the feature-branch-docker-image
*     to the target Jenkins CI environment so the PR change can be tested.
*   or
*   - When the PR belongs to a Manifest Repo, all the versions declared in the manifest
*     corresponding to the environment folder in the git diff should be injected into
*     the manifest of the selected Jenkins CI env.
*
* TL;DR
* Edits manifest of a service to provided branch
* Makes the edit in the cdis-manifest directory
**/

/**
* Replaces one specific service version according to the git diff from the service-specific repo PR.
* Only one service from the `versions` block of the selected Jenkins CI environment's manifest is modified.
*
* @param commonsHostname - hostname of commons to edit (e.g. qa-bloodpac.planx-pla.net)
* @param serviceName - defaults to conf.service
* @param quayBranchName - defaults to branch's name formatted
*/
def editService(String commonsHostname, String serviceName, String quayBranchName) {
  if (null == commonsHostname || null == serviceName || null == quayBranchName) {
    error("Missing parameter for editing manifest service:\n  commonsHostname: ${commonsHostname}\n  serviceName: ${serviceName}\n  quayBranchName: ${quayBranchName}")
  }
  dir("cdis-manifest/${commonsHostname}") {
    currentBranch = "${serviceName}:[a-zA-Z0-9._-]*"
    targetBranch = "${serviceName}:${quayBranchName}"
    echo "Editing cdis-manifest/${commonsHostname} service ${serviceName} to branch ${quayBranchName}"
    // swap current branch for the target branch
    // mariner-engine and mariner-s3sidecar need to be replaced in mariner.json file
    // TODO: Refactor this later to remove IF conditions per-service-name and come up with a generic way to mutate versions in split manifests
    // e.g., sh 'sed -i -e "s,'+"${currentBranch},${targetBranch}"+',g" ./manifests/'"${repoName}"'/'"${repoName}"'.json'
    // Assuming the folder name and the manifest name will always match the repo name
    if(serviceName == 'mariner-engine' || serviceName == 'mariner-s3sidecar'){
      sh 'sed -i -e "s,'+"${currentBranch},${targetBranch}"+',g" ./manifests/mariner/mariner.json'
      sh 'cat ./manifests/mariner/mariner.json'
    }else{
      sh 'sed -i -e "s,'+"${currentBranch},${targetBranch}"+',g" manifest.json'
      sh 'cat manifest.json'
    }
  }
}

/**
* Sets the banch-dictionary from the PR against the manifest of the CI environment.
*
* @param commonsHostname - hostname of commons to edit (e.g. jenkins-blood.planx-pla.net)
*/
def setDictionary(String commonsHostname) {
  def prBranchName = env.CHANGE_BRANCH
  def prRepoName = env.JOB_NAME.split('/')[1].replaceAll('-','');

  // branch dictionary
  def branchDictionary = "https://s3.amazonaws.com/dictionary-artifacts/${prRepoName}/${prBranchName}/schema.json"

  echo "Editing cdis-manifest/${commonsHostname} dictionary to set ${prBranchName}"

  // keep backup of the original manifest
  sh "cp cdis-manifest/${commonsHostname}/manifest.json cdis-manifest/${commonsHostname}/manifest.json.old"
  // swap current dictionary for the target dictionary
  sh(returnStatus: true, script: "cat cdis-manifest/${commonsHostname}/manifest.json.old | jq --arg theNewDict ${branchDictionary} '.global.dictionary_url |= \$theNewDict' > cdis-manifest/${commonsHostname}/manifest.json")

  sh "cat cdis-manifest/${commonsHostname}/manifest.json"
}

/**
* This function merges manifest changes. It checks if the environment's manifest found in manifest-repo PR
* contains certain blocks and replaces the same blocks in the Jenkins CI manifest.
* It also deletes blocks from the Jenkins CI environment to match the environment manifest that is
* being subjected to the PR check / testing.
*/
def mergeManifest(String changedDir, String selectedNamespace) {
  String od = sh(returnStdout: true, script: "jq -r .global.dictionary_url < tmpGitClone/$changedDir/manifest.json").trim()
  String pa = sh(returnStdout: true, script: "jq -r .global.portal_app < tmpGitClone/$changedDir/manifest.json").trim()
  // fetch netpolicy from the target environment
  sh(returnStatus : true, script: "if cat tmpGitClone/$changedDir/manifest.json | jq --exit-status '.global.netpolicy' >/dev/null; then "
    + "jq -r .global.netpolicy < tmpGitClone/$changedDir/manifest.json > netpolicy.json; "
    + "fi")
  
  // fetch sower block from the target environment
  sh "jq -r .sower < tmpGitClone/$changedDir/manifest.json > sower_block.json"

  def manifestBlockKeys = ["portal", "ssjdispatcher", "indexd", "metadata"]
  for (String item : manifestBlockKeys) {
    sh(returnStdout: true, script: "if cat tmpGitClone/${changedDir}/manifest.json | jq --exit-status '.${item}' >/dev/null; then "
      + "jq -r .${item} < tmpGitClone/${changedDir}/manifest.json > ${item}_block.json; "
      + "fi")
  }

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
          + """&& echo \$old | jq -r --arg od ${od} --arg pa ${pa} --argjson vs \"\$bs\""""
          + / '(.global.dictionary_url) |=/ + "\$od" + / | (.global.portal_app) |=/ + "\$pa"
          + / | (.versions) |=/ + "\$vs" + /'/ + " > cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json")
  String parseSowerBlockOutput = sh(returnStdout: true, script: "jq -r '.' sower_block.json").trim()
  println(parseSowerBlockOutput)
  if (parseSowerBlockOutput != "null") {
    // set Jenkins CI service accounts for sower jobs if the property exists
    sh(returnStdout: true, script: "cat sower_block.json | jq -r '.[] | if has(\"serviceAccountName\") then .serviceAccountName = \"jobs-${selectedNamespace}-planx-pla-net\" else . end' > new_scv_acct_sower_block.json")
    String sowerBlock2 = sh(returnStdout: true, script: "cat new_scv_acct_sower_block.json")
    println(sowerBlock2)
    sh(returnStdout: true, script: "cat new_scv_acct_sower_block.json | jq -s . > sower_block.json")
    String sowerBlock3 = sh(returnStdout: true, script: "cat sower_block.json")
    println(sowerBlock3)
    sh(returnStdout: true, script: "old=\$(cat cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json) && echo \$old | jq -r --argjson sj \"\$(cat sower_block.json)\" '(.sower) = \$sj' > cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json")
  }
  // replace netpolicy
  sh(returnStdout: true, script: "if [ -f \"netpolicy.json\" ]; then "
    + "old=\$(cat cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json) && echo \$old | jq --arg sp \"\$(cat netpolicy.json)\" '.global.netpolicy = \$sp' > cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json; "
    + "else "
    + "old=\$(cat cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json) && echo \$old | jq -r 'del(.global.netpolicy)' > cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json;"
    + "fi")
  // replace Portal block
  sh(returnStdout: true, script: "if [ -f \"portal_block.json\" ]; then "
    + "old=\$(cat cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json) && echo \$old | jq -r --argjson sp \"\$(cat portal_block.json)\" '(.portal) = \$sp' > cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json; "
    + "else "
    + "jq 'del(.portal)' cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json > manifest_tmp.json && mv manifest_tmp.json cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json; "
    + "fi")
    
  def manifestMergeBlockKeys = ["ssjdispatcher", "indexd", "metadata"]
  for (String item : manifestMergeBlockKeys) {
    sh(returnStdout: true, script: "if [ -f \"${item}_block.json\" ]; then "
      + "old=\$(cat cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json) && echo \$old | jq -r --argjson sp \"\$(cat ${item}_block.json)\" '(.${item}) = \$sp' > cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json; "
      + "fi")
  }

  String rs = sh(returnStdout: true, script: "cat cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json")
  return rs
}

/**
* Replaces the contents of environment-specific artifacts and config folders, e.g., etlMapping.yaml, portal/gitops.json, etc.
*/
def overwriteConfigFolders(String changedDir, String selectedNamespace) {
    List<String> folders = sh(returnStdout: true, script: "ls tmpGitClone/$changedDir").split()
    if (folders.contains('portal')) {
      println('Copying all the contents from tmpGitClone/$changedDir/portal into cdis-manifest/${selectedNamespace}.planx-pla.net/...')
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
    // Aggregate Metadata Config
    if (folders.contains('metadata')) {
      println("### Overwrite metadata folder ###")
      sh(script: "cp -rf tmpGitClone/$changedDir/metadata cdis-manifest/${selectedNamespace}.planx-pla.net/")
    }
    if (folders.contains('etlMapping.yaml')) {
      println('Copying etl mapping config from tmpGitClone/$changedDir/etlMapping.yaml into cdis-manifest/${selectedNamespace}.planx-pla.net/...')
      sh(script: "cp -rf tmpGitClone/$changedDir/etlMapping.yaml cdis-manifest/${selectedNamespace}.planx-pla.net/")
    }
    // List manifests folder
    println("###List manifests folder...")
    if(folders.contains('manifests')){
      List<String> manifests_sub_folders = sh(returnStdout: true, script: "ls tmpGitClone/$changedDir/manifests").split()
      // Overwrite mariner folder
      println("###Overwrite  mariner folder...")
      if(manifests_sub_folders.contains('mariner')){
        sh(returnStdout: true, script: "cp -rf tmpGitClone/$changedDir/manifests/mariner cdis-manifest/${selectedNamespace}.planx-pla.net/manifests")
        sh(returnStdout: true, script: "echo \$(cat tmpGitClone/$changedDir/manifests/mariner/mariner.json)")
        // replace s3 bucket
        println("###Replace s3 bucket in mariner.json...")
        config_location = "cdis-manifest/${selectedNamespace}.planx-pla.net/manifests/mariner/mariner.json"
        sh(returnStdout: true, script: "echo \$(cat ${config_location})")
        sh(returnStdout: true, script: "jq '.storage.s3.name=\"qaplanetv1--${selectedNamespace}--mariner-707767160287\"' ${config_location} > mariner_tmp.json && mv mariner_tmp.json ${config_location}")
      }
    }
  }

/**
* Iterates through the files listed in the PR change so they can be processed by the mergeManifest function.
* Its params and config blocks should be modified/removed to prepare the selected Jenkins CI environment for testing.
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

/**
* Returns the hostname of the mutated environment (currently used for nightly-build)
*/
def fetchHostnameFromMutatedEnvironment() {
  println('fetching mutatedEnvHostname from nightly.planx-pla.net/manifest.json...')
  def actualTestedEnv = sh(returnStdout: true, script: "jq -r .global.mutatedEnvHostname < tmpGitClone/nightly.planx-pla.net/manifest.json").trim()
  println("### ## found this hostname -> ${actualTestedEnv}")
  return actualTestedEnv
}
