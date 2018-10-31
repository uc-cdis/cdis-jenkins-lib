#!/usr/bin/groovy
package uchicago.cdis;
class MicroservicePipeline implements Serializable {
  Kube kube
  Map pipelineDefinition
  def steps

  MicroservicePipeline(steps, pipelineDefinition) {
    // Create a globally accessible variable that makes
    // the YAML pipeline definition available to all scripts
    this.pipelineDefinition = pipelineDefinition
    this.kube = new Kube()
    this.steps = steps
  }

  def execute() {
    switch(pipelineDefinition.myVariable) {
      case 'hello world':
        // Instantiate and execute a Python pipeline
        // new pythonPipeline(pipelineDefinition).executePipeline()
        kube.deploy()

      case 'nodejs':
        // Instantiate and execute a NodeJS pipeline
        new nodeJSPipeline(pipelineDefinition).executePipeline()
    }
  }
}

