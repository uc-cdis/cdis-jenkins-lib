# TL;DR

change!!

Some CDIS utility functions for Jenkins pipelines, as well as the base microservice pipeline along with some building block stages.  
To run a basic test suite locally on the CDIS utility functions, run
```
./build.sh
```

A test pipeline will automatically run when commits are made to PRs through the Jenkinsfile in this repository. The test pipeline uses the base microservice pipeline with injected variables to mock an actual pipeline running. Please make sure to edit the `Jenkinsfile` and manually change the branch that the library load call is directed to. 
See [here](https://jenkins.io/doc/book/pipeline/shared-libraries/) for more information about Jenkins Shared Libraries.

There is a conditional pipeline used for `cdis-manifest` or any repository having the same purpose called `ManifestBuild`.
