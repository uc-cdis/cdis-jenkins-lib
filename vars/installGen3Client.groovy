#!groovy

def call(String version = "latest") {
    String api_url = null

    // version: either Git tag or "latest"
    if (version == "latest") {
        api_url = "https://api.github.com/repos/uc-cdis/cdis-data-client/releases/latest"
    } else {
        api_url = "https://api.github.com/repos/uc-cdis/cdis-data-client/releases/tags/$version"
    }

    String download_url = sh(script: "curl -s $api_url | jq -r '.assets[] | select(.name | contains(\"linux\")) | .browser_download_url'", returnStdout: true)
    sh "wget $download_url"
    sh "unzip dataclient_linux.zip"
    sh "mv gen3-client /var/jenkins_home"
}
