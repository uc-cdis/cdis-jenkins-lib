#!groovy

def call(String from, String to) {
    sh "aws s3 cp $from $to --recursive"
}
