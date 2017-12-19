#!/bin/bash

cd "$(dirname $0)/src"
groovy -Djava.util.logging.config.file=../logging.properties uchicago/cdis/KubeHelperTests

