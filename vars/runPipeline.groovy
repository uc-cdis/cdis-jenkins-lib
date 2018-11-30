#!groovy

// This is the main entry point for all testing
def call(body) {
  // evaluate the body block, and collect configuration into the object
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  // call the pipeline indicated in config
  if (config.pipeline == 'microservice') {
    microservicePipeline3(config)
  }
  else if (config.pipeline == 'gitops') {
    gitopsPipeline(config)
  }
}