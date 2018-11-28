
def call() {
  node {
    steps {
      step('Deploy') {
        deploy()
      }
    }
  }
}