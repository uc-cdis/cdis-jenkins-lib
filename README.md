# TL;DR

bla
Base microservice pipeline and supporting library functions.
The `./Jenkinsfile` runs the pipeline against this repo.

## Pipeline Overview

A test pipeline will automatically run when commits are made to PRs through the Jenkinsfile in this repository. The test pipeline uses the base microservice pipeline with injected variables to mock an actual pipeline running. Please make sure to edit the `Jenkinsfile` and manually change the branch that the library load call is directed to. 
See [here](https://jenkins.io/doc/book/pipeline/shared-libraries/) for more information about Jenkins Shared Libraries.

There is a conditional pipeline used for `cdis-manifest` or any repository having the same purpose called `ManifestBuild`.

## Testing groovy code locally

The `./build.sh` shows one approach to running unit tests against
groovy helper functions outside of Jenkins

```
./build.sh
```
