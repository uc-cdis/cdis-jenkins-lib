def call() {
  node {
    stage('Deploy') {
      kubeHelper.deploy()
    }
  }
}