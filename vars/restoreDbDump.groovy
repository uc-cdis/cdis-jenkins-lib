#!groovy

def call(String dbDump) {
    withEnv(["GEN3_NOPROXY=true", "vpc_name=$env.KUBECTL_NAMESPACE", "GEN3_HOME=$env.WORKSPACE/cloud-automation"]) {
        echo "Rolling DB dump"
        sh "echo \"DROP SCHEMA public CASCADE;\" | bash cloud-automation/gen3/bin/psql.sh sheepdog"
        sh "echo \"CREATE SCHEMA public;\" | bash cloud-automation/gen3/bin/psql.sh sheepdog"
        sh "echo \"GRANT ALL ON SCHEMA public TO public;\" | bash cloud-automation/gen3/bin/psql.sh sheepdog"
        sh "echo \"GRANT ALL ON SCHEMA public TO sheepdog;\" | bash cloud-automation/gen3/bin/psql.sh sheepdog"
        sh "bash cloud-automation/gen3/bin/psql.sh sheepdog < $dbDump"
    }
}
