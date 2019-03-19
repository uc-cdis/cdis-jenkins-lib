def preBuildMaster() {
    def jobNameParts = JOB_NAME.tokenize('/') as String[]
    def jobName = jobNameParts.init().join('/')
    build "${jobName}/master"
}
