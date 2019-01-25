#!groovy

// This is the main entry point for all testing
def call(body) {
  println("run commit $env.GIT_COMMIT")
  println("run prev: $env.GIT_PREVIOUS_COMMIT")
  println(scm)
  // evaluate the body block, and collect configuration into the object
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  println("run commit $env.GIT_COMMIT")
  println("run prev: $env.GIT_PREVIOUS_COMMIT")
  // call the pipeline indicated in config
  if (config.pipeline == 'microservice') {
    microservicePipeline(config)
  }
  else if (config.pipeline == 'gitops') {
    gitopsPipeline(config)
  }
}