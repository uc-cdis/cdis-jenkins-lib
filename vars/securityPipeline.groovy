#!groovy

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.ChronoUnit

def call(body) {
  def config = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  // every Saturday at random time
  properties([pipelineTriggers([cron('H H * * 6')])])

  def jobTokens = JOB_NAME.tokenize('/') as String[]
  def name = jobTokens[1]

  node {
    // https://stackoverflow.com/a/43609466/1030110
    def isStartedByNonUser = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause) == null
    def firstRun = LocalDate.of(2019, Month.JUNE, 1)
    def today = LocalDate.now()

    println("Started by Non-User (regular run): ${isStartedByNonUser}")
    println("First run: ${firstRun}")
    println("Today is: ${today}")

    if (isStartedByNonUser && (today.getDayOfWeek() != DayOfWeek.SATURDAY)) {
        println("Running only on Saturdays")
        currentBuild.result = 'SUCCESS'
        return
    }

    def daysSinceFirstRun = ChronoUnit.DAYS.between(firstRun, today)
    println("Days since first run: ${daysSinceFirstRun}")
    if (isStartedByNonUser && ((daysSinceFirstRun % 14) != 0)) {
        println("Running only on second weeks")
        currentBuild.result = 'SUCCESS'
        return
    }

    stage('FetchCode') {
      dir(name) {
        checkout scm
      }
    }
    stage('ArchiveCode') {
      dir(name) {
        sh """
        args=""
        if [[ -f .secinclude ]]; then args+=" -i@.secinclude"; fi
        if [[ -f .secexclude ]]; then args+=" -x@.secexclude"; fi
        zip ../${name}.zip -r . \$args
        """
      }
    }
    stage('VeracodeScanning') {
      withCredentials([usernamePassword(credentialsId: 'Veracode', passwordVariable: 'VCPASS', usernameVariable: 'VCUSER')]) {
        veracode applicationName: name,
        canFailJob: true,
        criticality: 'High',
        fileNamePattern: '',
        replacementPattern: '',
        sandboxName: '',
        scanExcludesPattern: '',
        scanIncludesPattern: '',
        scanName: "${name}-\$timestamp",
        teams: '',
        timeout: 120,
        uploadExcludesPattern: '',
        uploadIncludesPattern: "${name}.zip",
        vid: '',
        vkey: '',
        vpassword: VCPASS,
        vuser: VCUSER,
        waitForScan: true
      }
    }
  }
}
