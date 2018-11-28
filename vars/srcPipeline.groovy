def call() {
  node {
    stage('Fetch') {
      fetchCode()
    }
    kubeHelper.kubectlNamespace = 'jenkins-brain'
    kubeHelper.setCloudAutomationPath("${env.WORKSPACE}/cloud-automation")
    stage('Deploy') {
      kubeHelper.deploy()
    }
  }
}