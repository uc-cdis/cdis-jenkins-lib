def call() {
  node {
    stage('Fetch') {
      fetchCode()
    }
    kubeHelper.kubectlNamespace = 'tsummer'
    kubeHelper.setCloudAutomationPath("${env.WORKSPACE}/cloud-automation")
    stage('Deploy') {
      kubeHelper.deploy()
    }
  }
}