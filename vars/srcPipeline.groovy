@Library('cdis-jenkins-lib@refactor/microservices') _

def call() {
  node {
    stage('Deploy') {
      deploy()
    }
  }
}