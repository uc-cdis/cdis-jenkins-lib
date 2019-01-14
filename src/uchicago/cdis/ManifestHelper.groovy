package uchicago.cdis;

/**
 * Groovy helper for deploying services to k8s in Jenkins pipelines.
 *
 * @see https://jenkins.io/doc/book/pipeline/shared-libraries/
 */
class ManifestHelper implements Serializable {
  def steps
  
  /**
   * Constructor
   *
   * @param steps injects hook to Jenkins Pipeline runtime
   */
  ManifestHelper(steps) { this.steps = steps; }

  def mergeManifest(String changedDir, String selectedNamespace) {
    String od = steps.sh(returnStdout: true, script: "jq -r .global.dictionary_url < $changedDir/manifest.json").trim()
    String pa = steps.sh(returnStdout: true, script: "jq -r .global.portal_app < $changedDir/manifest.json").trim()
    String s = steps.sh(returnStdout: true, script: "jq -r keys < cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json")
    println s
    def keys = new groovy.json.JsonSlurper().parseText(s)
    String dels = ""
    for (String k : keys) {
      if (steps.sh(returnStdout: true, script: "jq -r '.$k' < $changedDir/manifest.json").trim() == 'null') {
        if (dels == "")
          dels = dels + "del(.$k)"
        else
          dels = dels + " | del(.$k)"
      }
    }
    if (dels != "") {
      steps.sh(returnStdout: true, script: "old=\$(cat cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json) && echo \$old | jq -r \'${dels}\' > cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json")
    }
    steps.sh(returnStdout: true, script: "bs=\$(jq -r .versions < $changedDir/manifest.json) "
            + "&& old=\$(cat cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json) "
            + """&& echo \$old | jq -r --arg od ${od} --arg pa ${pa} --argjson vs \"\$bs\"""" 
            + / '(.global.dictionary_url) |=/ + "\$od" + / | (.global.portal_app) |=/ + "\$pa"
            + / | (.versions) |=/ + "\$vs" + /'/ + " > cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json")
    String rs = steps.sh(returnStdout: true, script: "cat cdis-manifest/${selectedNamespace}.planx-pla.net/manifest.json")
    return rs
  }

  def overwriteConfigFolders(String changedDir, String selectedNamespace) {
    List<String> folders = steps.sh(returnStdout: true, script: "ls $changedDir").split()
    if (folders.contains('arrangerProjects'))
      steps.sh(script: "cp -rf $changedDir/arrangerProjects cdis-manifest/${selectedNamespace}.planx-pla.net/")
    if (folders.contains('portal'))
      steps.sh(script: "cp -rf $changedDir/portal cdis-manifest/${selectedNamespace}.planx-pla.net/")
    if (folders.contains('etlMapping.yaml'))
      steps.sh(script: "cp -rf $changedDir/etlMapping.yaml cdis-manifest/${selectedNamespace}.planx-pla.net/")
  }
}
