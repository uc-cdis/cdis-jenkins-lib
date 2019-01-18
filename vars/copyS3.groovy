#!groovy

def call(String s3Resource, String output) {
    sh "aws s3 cp $s3Resource $output --recursive"
}
