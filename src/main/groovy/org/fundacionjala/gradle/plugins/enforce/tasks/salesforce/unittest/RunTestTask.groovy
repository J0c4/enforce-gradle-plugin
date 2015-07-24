/*
 * Copyright (c) Fundacion Jala. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package org.fundacionjala.gradle.plugins.enforce.tasks.salesforce.unittest

import com.sforce.soap.apex.*
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.fundacionjala.gradle.plugins.enforce.tasks.salesforce.SalesforceTask
import org.fundacionjala.gradle.plugins.enforce.testselector.ITestSelector
import org.fundacionjala.gradle.plugins.enforce.testselector.TestSelectorModerator
import org.fundacionjala.gradle.plugins.enforce.unittest.Apex.ApexClass
import org.fundacionjala.gradle.plugins.enforce.unittest.Apex.ApexClasses
import org.fundacionjala.gradle.plugins.enforce.unittest.Apex.ApexRunTestResult
import org.fundacionjala.gradle.plugins.enforce.unittest.RunTestListener
import org.fundacionjala.gradle.plugins.enforce.unittest.TestResultReport
import org.fundacionjala.gradle.plugins.enforce.unittest.report.HtmlManager
import org.fundacionjala.gradle.plugins.enforce.utils.Constants
import org.fundacionjala.gradle.plugins.enforce.utils.Util
import org.fundacionjala.gradle.plugins.enforce.utils.salesforce.MetadataComponents
import org.fundacionjala.gradle.plugins.enforce.wsc.rest.ToolingAPI
import org.fundacionjala.gradle.plugins.enforce.wsc.soap.ApexAPI
import org.gradle.api.logging.LogLevel
import org.gradle.logging.ProgressLoggerFactory

import java.nio.file.Paths

/**
 * This class generates the unit tests and coverage files
 * GP is a gradle properties
 */
class RunTestTask extends SalesforceTask {
    private static final String CLASS_PARAM = 'cls'
    private static final String CLASS_DIRECTORY = 'classes'
    private static final String WILDCARD_ALL_TEST = '*'
    private static final String NAME_FOLDER_REPORT = 'report'
    private static final String NAME_FOLDER_PAGES = 'pages'
    private static final String NAME_FILE = 'index.html'
    private static final String NAME_FILE_UNIT_TEXT_XML = 'unitTest.xml'
    private static final String NAME_FILE_COVERAGE_REPORT_XML = 'coverage.xml'
    private static final String PARAMETER_ASYNC = 'async'
    private static final String QUERY_CLASSES = "SELECT Id, Name FROM ApexClass"
    private static final String QUERY_TRIGGERS = "SELECT Id, Name FROM ApexTrigger"
    private static final String NOT_HAVE_UNIT_TEST_MESSAGE = "You don't have any test class to execute in your local repository"
    private static final String IS_TEST = "@isTest"
    private static final String UNIT_TEST_SUCCESS = 'Success'
    private static final String UNIT_TEST_FAIL = 'Fail'
    private static final String QUERY_COVERAGE = "SELECT NumLinesCovered, NumLinesUncovered, ApexClassorTriggerId, Coverage" +
            " FROM ApexCodeCoverageAggregate"
    private static final int TIME_RUN_TEST_ASYNC = 1000
    private static final int ZERO_NUMBER = 0
    private String pathClasses
    private HtmlManager htmlManager
    private ITestSelector testSelector
    public static final String TEST_MESSAGE = "Unit Test Results"
    Boolean async
    String jsonByClasses
    String jsonCoverageLines
    String jsonByTriggers
    FileWriter fileWriterReport
    String folderReport
    ToolingAPI toolingAPI
    ApexAPI apexAPI
    RunTestListener runTestListener
    TestResultReport testResultReport

    ArrayList<ApexRunTestResult> apexTestResultArrayList

    def factory = services.get(ProgressLoggerFactory)
    def progressLogger = factory.newOperation('some.log.category')

    /**
     * Sets description and group task
     * @param description is description tasks
     * @param group is the group typeName the task
     */
    RunTestTask() {
        super("This task runs unit tests and it also generates results of unit test and coverage", "Test")
    }

    /**
     * Prepares directory for reports
     */
    @Override
    void setup() {
        pathClasses = Paths.get(projectPath, CLASS_DIRECTORY).toString()
        folderReport = Paths.get(buildFolderPath, NAME_FOLDER_REPORT).toString()
        fileManager.createNewDirectory(folderReport)
        String folderPages = Paths.get(folderReport, NAME_FOLDER_PAGES).toString()
        fileManager.createNewDirectory(folderPages)
        fileWriterReport = new FileWriter(Paths.get(folderReport, NAME_FILE).toString())
        testResultReport = new TestResultReport(folderReport)
        apexTestResultArrayList = new ArrayList<ApexRunTestResult>()

        htmlManager = new HtmlManager(fileWriterReport)
        htmlManager.pathReport = folderReport
        htmlManager.sourceCode = projectPath
        htmlManager.folderPages = folderPages

        toolingAPI = new ToolingAPI(credential)
        apexAPI = new ApexAPI(credential)
        jsonByClasses = toolingAPI.httpAPIClient.executeQuery(QUERY_CLASSES)
    }

    @Override
    void loadParameters() {
        if (Util.isValidProperty(project, PARAMETER_ASYNC) &&
                project.properties[PARAMETER_ASYNC].toString().equals("true")) {
            async = true
        }
    }

    /**
     * Injects a ITestSelector instance for test purposes
     * @param testSelector the instance to be injected
     */
    protected void setTestSelector(ITestSelector testSelector) {
        this.testSelector = testSelector;
    }

    /**
     * Generates the unit test and coverage files
     */
    @Override
    void runTask() {
        if (!ApexClasses.checkForRecords(jsonByClasses)) {
            throw new Exception("Not found any class in your organization")
        }

        jsonByTriggers = toolingAPI.httpAPIClient.executeQuery(QUERY_TRIGGERS)

        testSelector = TestSelectorModerator.instance.getTestSelector(project, toolingAPI, pathClasses)
        ArrayList<String> classes = getClassNames()
        if (async) {
            if (classes.empty) {
                logger.error(NOT_HAVE_UNIT_TEST_MESSAGE)
                return
            }
            runTestAsynchronous(classes)
        } else {
            runTestsSynchronous(classes)
        }
        writeJenkinsPluginJson()
        generateUnitTestReportXml()
        deleteTemporaryFiles()
    }

    /**
     * Runs test synchronously
     * @param classes is type array of classes name
     */
    def runTestsSynchronous(ArrayList<String> classes) {
        RunTestsRequest request = new RunTestsRequest()
        if (classes && !classes.isEmpty()) {
            logger.quiet("${classes.size()} classes tests will be executed")
            request.classes = classes
        } else {
            logger.quiet("All unit test will be executed")
            request.allTests = true
        }
        logger.log(LogLevel.INFO, String.format("Start time: %s", new Date().format("HH:mm:ss.SSS")))
        RunTestsResult runTestResult = apexAPI.runTests(request)
        logger.log(LogLevel.INFO, String.format("Finish time: %s", new Date().format("HH:mm:ss.SSS")))
        String timeResult = Util.formatDurationHMS(runTestResult.totalTime as long)
        logger.log(LogLevel.INFO, "total time:  $timeResult")

        if (runTestResult && runTestResult.failures && runTestResult.failures.size() > ZERO_NUMBER) {
            logger.log(LogLevel.LIFECYCLE, "${TEST_MESSAGE}:\n")
        }

        runTestResult.failures.each { testFailures ->
            ApexRunTestResult apexRunTestResult = new ApexRunTestResult()
            apexRunTestResult.outcome = UNIT_TEST_FAIL
            apexRunTestResult.stackTrace = testFailures.stackTrace
            apexRunTestResult.TestTimestamp = testFailures.time
            apexRunTestResult.methodName = testFailures.methodName
            apexRunTestResult.apexClassId = testFailures.id
            apexRunTestResult.message = testFailures.message
            apexRunTestResult.className = testFailures.name
            printTestFailure(testFailures)
            apexTestResultArrayList.push(apexRunTestResult)
        }

        runTestResult.successes.each { testFailures ->
            ApexRunTestResult apexRunTestResult = new ApexRunTestResult()
            apexRunTestResult.outcome = UNIT_TEST_SUCCESS
            apexRunTestResult.stackTrace = ""
            apexRunTestResult.TestTimestamp = testFailures.time
            apexRunTestResult.methodName = testFailures.methodName
            apexRunTestResult.apexClassId = testFailures.id
            apexRunTestResult.className = testFailures.name
            apexTestResultArrayList.push(apexRunTestResult)
        }

        logger.log(LogLevel.INFO, "start generate report html")
        htmlManager.generateReport(verifyExistFileCoverage(runTestResult.codeCoverage),
                apexTestResultArrayList, runTestResult.failures.size(), runTestResult.successes.size())
        fileWriterReport.close()
    }

    /**
     * Prints one test failure result from RunTestResult object
     * @param runTestFailure the test failure object to print its properties
     */
    void printTestFailure(RunTestFailure runTestFailure) {
        if (runTestFailure) {
            String errorMessage = runTestFailure.message?"\n\t\tMessage: ${runTestFailure.message}":""
            errorMessage = runTestFailure.stackTrace?"${errorMessage}\n\t\tStacktrace: ${runTestFailure.stackTrace}":errorMessage
            if(!errorMessage.empty) {
                String message = "\t${runTestFailure.name}.${runTestFailure.methodName}${errorMessage}\n"
                logger.quiet(message)
            }
        }
    }

    /**
     * Writes a new json file with coverage data
     */
    private void writeJenkinsPluginJson() {
        if (htmlManager && htmlManager.coverageLoader) {
            logger.log(LogLevel.INFO, "Starting to write JSON for jenkins plugin...")
            JsonBuilder jsonBuilder = new JsonBuilder()
            String chartName = Constants.JENKINS_CHART_NAME
            jsonBuilder.call(title: chartName, data: htmlManager.coverageLoader.loadArrayChartPie(),
                    coverageData: htmlManager.coverageLoader.loadArrayChartCoverage())
            File jsonFile = new File(Paths.get(folderReport, Constants.JENKINS_JSON_FILE_NAME).toString())
            jsonFile.write(jsonBuilder.toPrettyString().replace("\'", ""))
            logger.log(LogLevel.INFO, "JSON created at: ${jsonFile.path}")
        } else {
            logger.log(LogLevel.INFO, "No data to write JSON for jenkins plugin")
        }
    }

    CodeCoverageResult[] verifyExistFileCoverage(CodeCoverageResult[] coverageResult) {
        ArrayList<CodeCoverageResult> codeCoverageResults = []
        coverageResult.each { coverageToValidate ->

            String path
            String extension

            if (coverageToValidate.type.equals(Constants.TYPE_CLASS)) {
                path = Paths.get(projectPath, MetadataComponents.CLASSES.getDirectory()).toString()
                extension = MetadataComponents.CLASSES.getExtension()
            } else {
                path = Paths.get(projectPath, MetadataComponents.TRIGGERS.getDirectory()).toString()
                extension = MetadataComponents.TRIGGERS.getExtension()
            }

            path = Paths.get(path, "${coverageToValidate.getName()}.$extension").toString()

            if (new File(path).exists()) {
                codeCoverageResults.push(coverageToValidate)
            }
        }
        return codeCoverageResults.toArray()
    }

    CodeCoverageResult[] getCodeCoverageResult() {
        ArrayList<CodeCoverageResult> coverageResultArrayList = []
        JsonSlurper jsonSlurper = new JsonSlurper()
        jsonSlurper.parseText(jsonCoverageLines).records.each { coverageResult ->
            if (coverageResult.NumLinesUncovered || coverageResult.NumLinesCovered) {
                CodeCoverageResult codeCoverageResult = new CodeCoverageResult()
                codeCoverageResult.numLocations = coverageResult.NumLinesCovered + coverageResult.NumLinesUncovered
                codeCoverageResult.numLocationsNotCovered = coverageResult.NumLinesUncovered
                String nameApex = getApexNameByJson(coverageResult.ApexClassOrTriggerId as String, jsonByClasses)
                if (!nameApex.isEmpty()) {
                    codeCoverageResult.name = nameApex
                    codeCoverageResult.type = Constants.TYPE_CLASS
                } else {
                    nameApex = getApexNameByJson(coverageResult.ApexClassOrTriggerId as String, jsonByTriggers)
                    codeCoverageResult.name = nameApex
                }
                ArrayList<CodeLocation> codeLocationArrayList = []
                coverageResult.Coverage.uncoveredLines.each { line ->
                    CodeLocation codeLocation = new CodeLocation()
                    codeLocation.line = line
                    codeLocationArrayList.push(codeLocation)
                }

                codeCoverageResult.locationsNotCovered = codeLocationArrayList.toArray()
                coverageResultArrayList.push(codeCoverageResult)
            }
        }

        return coverageResultArrayList.toArray()
    }

    /**
     * Seeks an Id in the object Json
     * @param Id is a identifier of class or trigger
     * @param json is the result of a query to salesforce
     * @return a name class or trigger
     */
    public static String getApexNameByJson(String Id, String json) {
        String nameApex = ""
        JsonSlurper jsonSlurper = new JsonSlurper()
        for (elementSalesforce in jsonSlurper.parseText(json).records) {
            if (elementSalesforce.Id == Id) {
                nameApex = elementSalesforce.Name
                break
            }
        }

        return nameApex
    }

    /**
     * Runs test asynchronously
     * @param classes is type array of classes name
     */
    def runTestAsynchronous(ArrayList<String> classes) {
        if (classes && !classes.size()) {
            throw new Exception('Not found class for execute unit test in your local repository')
        }
        org.fundacionjala.gradle.plugins.enforce.wsc.soap.ToolingAPI toolingAPISoap
        toolingAPISoap = new org.fundacionjala.gradle.plugins.enforce.wsc.soap.ToolingAPI(credential)
        ApexClasses apexClasses = new ApexClasses()
        apexClasses.load(jsonByClasses, classes)

        runTestListener = new RunTestListener(toolingAPISoap, System.out, apexClasses)
        runTestListener.startUnitTestExecution()

        progressLogger.description = "Sleeping"
        progressLogger.started()

        while (!runTestListener.done) {
            sleep(TIME_RUN_TEST_ASYNC)
            progressLogger.progress("$toolingAPISoap.numberUnitTest/$toolingAPISoap.currentUnitTestCompleted")
        }

        progressLogger.completed()
        jsonCoverageLines = toolingAPI.httpAPIClient.executeQuery(QUERY_COVERAGE)
        apexTestResultArrayList = runTestListener.apexTestItem.apexTestResults
        generateHtmlReportCoverageAsync(apexClasses)
        generateCoverageReportXml()
    }

    /**
     * Generates report coverage in html format using json objects
     */
    def generateHtmlReportCoverageAsync(ApexClasses apexClasses) {
        int unitTestFail = 0
        int unitTestSuccess = 0

        apexTestResultArrayList.each { apexTestResult ->
            if (apexTestResult.outcome == UNIT_TEST_FAIL) {
                unitTestFail++
            } else {
                unitTestSuccess++
            }
        }
        apexTestResultArrayList.each {apexTestResult->
            ApexClass apexClass = apexClasses.getClass(apexTestResult.apexClassId)
            apexTestResult.className = apexClass?apexClass.name:apexTestResult.className
        }
        htmlManager.generateReport(verifyExistFileCoverage(getCodeCoverageResult()),
                apexTestResultArrayList, unitTestFail, unitTestSuccess)
        fileWriterReport.close()
    }

    /**
     * Generates report unit test in xml format using json objects
     */
    def generateUnitTestReportXml() {
        testResultReport.loadInformationUnitTest(apexTestResultArrayList, jsonByClasses)
        FileWriter unitTestXML = new FileWriter(Paths.get(folderReport, NAME_FILE_UNIT_TEXT_XML).toString())
        testResultReport.generateUnitTestXML(unitTestXML)
        unitTestXML.close()
    }

    /**
     * Generates report coverage in xml format using json objects
     */
    def generateCoverageReportXml() {
        testResultReport.loadInformationCoverage(jsonCoverageLines, jsonByClasses, jsonByTriggers, projectPath)
        FileWriter coverageReportXML = new FileWriter(Paths.get(folderReport,
                NAME_FILE_COVERAGE_REPORT_XML).toString())
        testResultReport.generateCoverageReportXML(coverageReportXML)
        coverageReportXML.close()
    }

    /**
     * Gets all test class names to be executed
     */
    public ArrayList<String> getClassNames() {
        return testSelector.getTestClassNames();
    }
}