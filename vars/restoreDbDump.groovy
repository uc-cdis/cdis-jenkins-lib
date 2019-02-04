#!groovy

def call(String kubectlNamespace, String dbDump) {
    vpc_name = "qaplanetv1"
    withEnv(["GEN3_NOPROXY=true", "vpc_name=${vpc_name}", "GEN3_HOME=$env.WORKSPACE/cloud-automation", "KUBECTL_NAMESPACE=${kubectlNamespace}"]) {
        echo "Rolling DB dump"
        sh "echo \"SELECT current_database();\" | bash cloud-automation/gen3/bin/psql.sh sheepdog"
        sh "echo \"DROP SCHEMA public CASCADE;\" | bash cloud-automation/gen3/bin/psql.sh sheepdog"
        sh "echo \"CREATE SCHEMA public;\" | bash cloud-automation/gen3/bin/psql.sh sheepdog"
        sh "echo \"GRANT ALL ON SCHEMA public TO public;\" | bash cloud-automation/gen3/bin/psql.sh sheepdog"
        sh "echo \"GRANT ALL ON SCHEMA public TO sheepdog;\" | bash cloud-automation/gen3/bin/psql.sh sheepdog"
        sh "bash cloud-automation/gen3/bin/psql.sh sheepdog < $dbDump"
    }
}
