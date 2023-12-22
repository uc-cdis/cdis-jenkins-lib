#!groovy

def call(String from, String to, String recursive = "") {
    sh 'aws s3 cp $from $to --no-progress ${recursive}'
}
