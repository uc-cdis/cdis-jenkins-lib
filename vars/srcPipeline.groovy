
def call() {
  node {
    stage('Deploy') {
      deploy()
    }
  }
}