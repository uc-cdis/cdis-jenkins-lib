def writeMetricWithResult(stageName, isSuccess) {
  measure = isSuccess ? "pass_count" : "fail_count"
  def REPO_NAME = env.JOB_NAME.split('/')[1];
  def PR_NUMBER = env.BRANCH_NAME.split('-')[1];
  
  def output = sh(script: "curl -i -XPOST "http://influxdb:8086/write?db=ci_metrics" --data-binary "${measure},test_name=${stageName},repo_name=${REPO_NAME},pr_num=${PR_NUMBER} ${measure}=1", returnStdout: true);

  println(output)
}
