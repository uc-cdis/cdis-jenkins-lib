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

  // enabling this now across the board to eliminate selenium-hub + nodes flakyness
  // microservicePipelineK8s(config)

  
  if (prLabels.any{label -> label.name == "gen3-qa-in-a-box"}) {
    println('Found [gen3-qa-in-a-box] label, running CI on an ephemeral pod with selenium-standalone...')

    microservicePipelineK8s(config)
  } else {
    println('Running un-kubernetisized legacy pipeline...')
    microservicePipeline(config)
  }
}
