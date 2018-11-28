def call() {
  node {
    stage('Fetch') {
      gitHelper.fetchAll()
    }
    kubeHelper.kubectlNamespace = 'jenkins-brain'
    kubeHelper.setCloudAutomationPath("${env.WORKSPACE}/cloud-automation")
    stage('Deploy') {
      kubeHelper.deploy()
    }
  }
}