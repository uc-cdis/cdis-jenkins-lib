#!groovy

def call() {
    sh "rm dataclient_linux.zip"
    sh "rm /var/jenkins_home/gen3-client"
}
