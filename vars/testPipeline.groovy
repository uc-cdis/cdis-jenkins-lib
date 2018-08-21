#!groovy

def call() {
  pipeline {
    agent any
  
    environment {
      QUAY_API = 'https://quay.io/api/v1/repository/cdis/'
    }
  
    stages {
      stage('TestStage') {
        echo "https://jenkins.planx-pla.net/ $env.JOB_NAME ran in testStage"
      }
    }
  }
}