#!/usr/bin/groovy
package uchicago.cdis;
class MicroservicePipeline implements Serializable {
  Kube kube

  MicroservicePipeline(pipelineDefinition) {
    // Create a globally accessible variable that makes
    // the YAML pipeline definition available to all scripts
    // pd = pipelineDefinition
    kube = new Kube()
  }

  def execute() {
    node {
      stage('Run Tests') {
        echo "RUNNING THE TEST"
        kube.deploy()
      }

      if (signalSlack) {
        stage('Deploy') {
          sh "path/to/a/deploy/bash/script.sh ${pd.deploymentEnvironment}"
        }
      }
    }
  }
}

