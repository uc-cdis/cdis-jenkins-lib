#!/usr/bin/groovy
package uchicago.cdis;

Kube kube

MicroservicePipeline(pipelineDefinition) {
  // Create a globally accessible variable that makes
  // the YAML pipeline definition available to all scripts
  pd = pipelineDefinition
  kube = new Kube()
}

def executePipeline() {
  node {
      stage('Run Tests') {
        kube.deploy()
      }
  }
}

//#!/usr/bin/groovy
//package uchicago.cdis;
//class MicroservicePipeline implements Serializable {
//  Kube kube
//  Map pipelineDefinition
//  def steps

//  MicroservicePipeline(pipelineDefinition) {
//    // Create a globally accessible variable that makes
//    // the YAML pipeline definition available to all scripts
//    this.pipelineDefinition = pipelineDefinition
//    this.kube = new Kube()
//  }




return this
