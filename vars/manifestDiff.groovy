#!groovy

def call() {
  script {
    def gitHelper = new uchicago.cdis.GitHelper(this)
    def manifestHelper = new uchicago.cdis.ManifestHelper(this)
    HashMap fileChanges = gitHelper.getLatestChangeOfBranch('HEAD')
    for (String key : fileChanges.keySet())
    {
      if (key != '.')
      {
        String rs = manifestHelper.mergeManifest(key, env.KUBECTL_NAMESPACE)
        echo "new manifest $rs"
        manifestHelper.overwriteConfigFolders(key, env.KUBECTL_NAMESPACE)
        break;
      }
    }
  }
}
