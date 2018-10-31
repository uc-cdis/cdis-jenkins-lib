#!/bin/groovy
package uchicago.cdis;

class StdPipeline implements Serializable {
  def myVariable
}

def execute(Map pipelineDefinition) {

  node {

    stage('Initialize') {
      // checkout scm
      echo 'Loading pipeline definition'
      // Yaml parser = new Yaml()
      // Map pipelineDefinition = parser.load(new File(pwd() + '/pipeline.yml').text)
    }

    switch('hello world') {
      case 'hello world':
        // Instantiate and execute a Python pipeline
        new MicroservicePipeline(pipelineDefinition).executePipeline()
      case 'nodejs':
        // Instantiate and execute a NodeJS pipeline
        // new nodeJSPipeline(pipelineDefinition).executePipeline()
        echo "Hello world"
    }

  }

}