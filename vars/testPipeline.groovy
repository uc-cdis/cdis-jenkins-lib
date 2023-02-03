#!groovy

// This is the main entry point for all testing
def call(body) {
  // evaluate the body block, and collect configuration into the object
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST

  // Our CI Pipeline is heavily parameterized based on Pull Request labels
  // giving a chance for auto-label gh actions to catch up
  sleep(30)
  def prLabels = githubHelper.fetchLabels()
  config['prLabels'] = prLabels
  body.delegate = config
  body()

  microservicePipeline(config)
}
