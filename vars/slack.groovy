def sendUnstable() {
  slackSend(color: 'bad', message: "https://jenkins.planx-pla.net $env.JOB_NAME pipeline UNSTABLE")
}

def sendFailure() {
  slackSend(color: 'bad', message: "https://jenkins.planx-pla.net $env.JOB_NAME pipeline FAILED")
}

def sendSuccess() {
  slackSend(color: 'good', message: "https://jenkins.planx-pla.net $env.JOB_NAME pipeline SUCCESS")
}