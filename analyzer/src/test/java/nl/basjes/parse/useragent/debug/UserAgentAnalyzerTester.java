/*
 * Yet Another UserAgent Analyzer
 * Copyright (C) 2013-2017 Niels Basjes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nl.basjes.parse.useragent.debug;

import nl.basjes.parse.useragent.ResourceLoader;
import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static nl.basjes.parse.useragent.UserAgent.NULL_VALUE;
import static nl.basjes.parse.useragent.UserAgent.SYNTAX_ERROR;

public class UserAgentAnalyzerTester extends UserAgentAnalyzer {
    private static final Logger LOG = LogManager.getLogger(UserAgentAnalyzerTester.class);
    private List<Map<String, Map<String, String>>> testCases;

    private final static LoggerConfig verboseLogger = createVerboseLogger();

    private static LoggerConfig createVerboseLogger() {
        Configuration config = LoggerContext.getContext(false).getConfiguration();
        List<AppenderRef> rootRefs = config.getRootLogger().getAppenderRefs();
        AppenderRef[] refs = new AppenderRef[rootRefs.size()];
        int i = 0;
        for (AppenderRef rootRef : rootRefs)
            refs[i++] = AppenderRef.createAppenderRef(rootRef.getRef(), null, null);

        LoggerConfig loggerConfig = LoggerConfig.createLogger(false, Level.DEBUG,
            "nl.basjes.parse.useragent", "true", refs, null, config, null );
        for (AppenderRef ref : refs)
            loggerConfig.addAppender(config.getAppender(ref.getRef()), null, null);

        return loggerConfig;
    }

    public UserAgentAnalyzerTester() {
        super();
    }

    public UserAgentAnalyzerTester(String resourceString, List<String> wantedFields, boolean showMatcherStats) {
        super(resourceString, wantedFields, showMatcherStats);
    }

    public UserAgentAnalyzerTester(String resourceString) {
        this(resourceString, true);
    }

    public UserAgentAnalyzerTester(String resourceString, boolean showMatcherStats) {
        this(resourceString, null, showMatcherStats);
    }

    public UserAgentAnalyzerTester(boolean showMatcherStats) {
        this("classpath*:UserAgents/**/*.yaml", showMatcherStats);
    }


    @Override
    protected ResourceLoader load(String resourceString, List<String> wantedFields, boolean showMatcherStats) {
        ResourceLoader loader = super.load(resourceString, wantedFields, showMatcherStats);
        initTests(loader);
        return loader;
    }

    public void initTests(ResourceLoader loader) {
        testCases = loader.testCases;
    }

    class TestResult {
        String field;
        String expected;
        String actual;
        boolean pass;
        boolean warn;
        long confidence;
    }

    /**
     * Run all the test_cases available.
     *
     * @return true if all tests were successful.
     */
    @SuppressWarnings({"unused"})
    public boolean runTests() {
        return runTests(false, true);
    }

    public boolean runTests(boolean showAll, boolean failOnUnexpected) {
        return runTests(showAll, failOnUnexpected, null, false, true);
    }

    @Override
    public UserAgent createUserAgent(String userAgentString) {
        return new DebugUserAgent((userAgentString));
    }


    public void setVerbose(boolean enable) {
        LoggerContext loggerContext = LoggerContext.getContext(false);
        Configuration config = loggerContext.getConfiguration();
        if (enable == config.getLoggers().containsKey(verboseLogger.getName())) return;
        if (enable) config.addLogger(verboseLogger.getName(), verboseLogger);
        else config.removeLogger(verboseLogger.getName());
    }

    public boolean runTests(boolean showAll,
                            boolean failOnUnexpected,
                            Collection<String> onlyValidateFieldNames,
                            boolean measureSpeed,
                            boolean showPassedTests) {
        boolean allPass = true;
        if (testCases == null) {
            return allPass;
        }
        List<TestResult> results = new ArrayList<>(32);

        String filenameHeader = "Test number and source";
        int filenameHeaderLength = filenameHeader.length();
        int maxFilenameLength = filenameHeaderLength;
        for (Map<String, Map<String, String>> test : testCases) {
            Map<String, String> metaData = test.get("metaData");
            String filename = metaData.get("filename");
            maxFilenameLength = Math.max(maxFilenameLength, filename.length());
        }

        maxFilenameLength+=11;

        StringBuilder sb = new StringBuilder(1024);

        sb.append("| ").append(filenameHeader);
        for (int i = filenameHeaderLength; i < maxFilenameLength; i++) {
            sb.append(' ');
        }

        sb.append("|S|AA|MF|");
        if (measureSpeed) {
            sb.append("  PPS| msPP|");
        }
        sb.append("--> S=Syntax Error, AA=Number of ambiguities during parse, MF=Matches Found");
        if (measureSpeed) {
            sb.append(", PPS=parses/sec, msPP=milliseconds per parse");
        }

        if (showPassedTests) {
            LOG.info("+===========================================================================================");
            LOG.info(sb.toString());
            LOG.info("+-------------------------------------------------------------------------------------------");
        }

        int testcount = 0;
        for (Map<String, Map<String, String>> test : testCases) {
            testcount++;
            Map<String, String> input = test.get("input");
            Map<String, String> expected = test.get("expected");

            @SuppressWarnings("unchecked")

            List<String> options = null;
            if (test.containsKey("options")) {
                options = new ArrayList<>(test.get("options").keySet());
            }
            Map<String, String> metaData = test.get("metaData");
            String filename = metaData.get("filename");
            String linenumber = metaData.get("fileline");

            setVerbose(options != null && options.contains("verbose"));
            boolean init = (options != null && options.contains("init"))
                || expected == null || expected.size() == 0;

            String testName = input.get("name");
            String userAgentString = input.get("user_agent_string");

            if (testName == null) {
                testName = userAgentString;
            }

            sb.setLength(0);

            sb.append("|").append(String.format("%4d", testcount))
              .append(".(").append(filename).append(':').append(linenumber).append(')');
            for (int i = filename.length()+linenumber.length()+7; i < maxFilenameLength; i++) {
                sb.append(' ');
            }

            DebugUserAgent agent = null;
            long measuredSpeed=-1;
            if (measureSpeed) {
                // Preheat
                for (int i = 0; i < 100; i++) {
                    agent = (DebugUserAgent) parse(userAgentString);
                }
                long startTime = System.nanoTime();
                for (int i = 0; i < 1000; i++) {
                    agent = (DebugUserAgent) parse(userAgentString);
                }
                long stopTime = System.nanoTime();
                measuredSpeed = (1000000000L*(1000))/(stopTime-startTime);
            } else {
                agent = (DebugUserAgent) parse(userAgentString);
            }

            sb.append('|');
            if (agent.hasSyntaxError()) {
                sb.append('S');
            } else {
                sb.append(' ');
            }
            if (agent.hasAmbiguity()) {
                sb.append(String.format("|%2d", agent.getAmbiguityCount()));
            } else {
                sb.append("|  ");
            }

            sb.append(String.format("|%2d", agent.getNumberOfAppliedMatches()));

            if (measureSpeed) {
                sb.append('|').append(String.format("%5d", measuredSpeed));
                sb.append('|').append(String.format("%5.2f", 1000.0/measuredSpeed));
            }

            sb.append("| ").append(testName);

            // We create the log line but we keep it untill we know it actually must be output to the screen
            String testLogLine = sb.toString();

            sb.setLength(0);

            boolean pass = true;
            results.clear();

            if (init) {
                LOG.info(testLogLine);
                sb.append(agent.toYamlTestCase());
                LOG.info(sb.toString());
//                return allPass;
            } else {
                if (expected == null) {
                    LOG.info(testLogLine);
                    LOG.warn("| - No expectations ... ");
                    continue;
                }
            }

            int maxNameLength = 6; // "Field".length()+1;
            int maxActualLength = 7; // "Actual".length()+1;
            int maxExpectedLength = 9; // "Expected".length()+1;

            if (expected != null) {
                List<String> fieldNames = agent.getAvailableFieldNamesSorted();

                if (onlyValidateFieldNames != null && onlyValidateFieldNames.isEmpty()) {
                    onlyValidateFieldNames = null;
                } else if (onlyValidateFieldNames != null) {
                    fieldNames.clear();
                    fieldNames.addAll(onlyValidateFieldNames);
                }

                for (String newFieldName: expected.keySet()) {
                    if (!fieldNames.contains(newFieldName)) {
                        fieldNames.add(newFieldName);
                    }
                }

                for (String fieldName : fieldNames) {
                    // Only check the desired fieldnames
                    if (onlyValidateFieldNames != null &&
                        !onlyValidateFieldNames.contains(fieldName)) {
                        continue;
                    }

                    TestResult result = new TestResult();
                    result.field = fieldName;
                    boolean expectedSomething;

                    // Actual value
                    result.actual = agent.getValue(result.field);
                    result.confidence = agent.getConfidence(result.field);
                    if (result.actual == null) {
                        result.actual = NULL_VALUE;
                    }

                    // Expected value
                    String expectedValue = expected.get(fieldName);
                    if (expectedValue == null) {
                        expectedSomething = false;
                        if (result.confidence < 0) {
                            continue; // A negative value really means 'absent'
                        }
                        result.expected = "<<absent>>";
                    } else {
                        expectedSomething = true;
                        result.expected = expectedValue;
                    }

                    result.pass = result.actual.equals(result.expected);
                    if (!result.pass) {
                        result.warn=true;
                        if (expectedSomething) {
                            result.warn=false;
                            pass = false;
                            allPass = false;
                        } else {
                            if (failOnUnexpected) {
                                // We ignore this special field
                                if (!SYNTAX_ERROR.equals(result.field)) {
                                    result.warn = false;
                                    pass = false;
                                    allPass = false;
                                }
                            }
                        }
                    }

                    results.add(result);

                    maxNameLength = Math.max(maxNameLength, result.field.length());
                    maxActualLength = Math.max(maxActualLength, result.actual.length());
                    maxExpectedLength = Math.max(maxExpectedLength, result.expected.length());
                }

                if (!agent.analyzeMatchersResult()) {
                    pass = false;
                    allPass = false;
                }
            }

            if (!init && pass && !showAll) {
                if (showPassedTests) {
                    LOG.info(testLogLine);
                }
                continue;
            }

            if (!pass) {
                LOG.info(testLogLine);
                LOG.error("| TEST FAILED !");
            }

            if (agent.hasAmbiguity()) {
                LOG.info("| Parsing problem: Ambiguity {} times. ", agent.getAmbiguityCount());
            }
            if (agent.hasSyntaxError()) {
                LOG.info("| Parsing problem: Syntax Error");
            }

            if (init || !pass) {
                sb.setLength(0);
                sb.append("\n");
                sb.append("\n");
                sb.append("- matcher:\n");
                sb.append("#    options:\n");
                sb.append("#    - 'verbose'\n");
                sb.append("    require:\n");
                for (String path : getAllPaths(userAgentString)) {
                    if (path.contains("=\"")) {
                        sb.append("#    - '").append(path).append("'\n");
                    }
                }
                sb.append("    extract:\n");
                sb.append("#    - 'DeviceClass           :   1:' \n");
                sb.append("#    - 'DeviceBrand           :   1:' \n");
                sb.append("#    - 'DeviceName            :   1:' \n");
                sb.append("#    - 'OperatingSystemClass  :   1:' \n");
                sb.append("#    - 'OperatingSystemName   :   1:' \n");
                sb.append("#    - 'OperatingSystemVersion:   1:' \n");
                sb.append("#    - 'LayoutEngineClass     :   1:' \n");
                sb.append("#    - 'LayoutEngineName      :   1:' \n");
                sb.append("#    - 'LayoutEngineVersion   :   1:' \n");
                sb.append("#    - 'AgentClass            :   1:' \n");
                sb.append("#    - 'AgentName             :   1:' \n");
                sb.append("#    - 'AgentVersion          :   1:' \n");
                sb.append("\n");
                sb.append("\n");
                LOG.info(sb.toString());
            }

            sb.setLength(0);
            sb.append("+--------+-");
            for (int i = 0; i < maxNameLength; i++) {
                sb.append('-');
            }
            sb.append("-+-");
            for (int i = 0; i < maxActualLength; i++) {
                sb.append('-');
            }
            sb.append("-+------------+-");
            for (int i = 0; i < maxExpectedLength; i++) {
                sb.append('-');
            }
            sb.append("-+");

            String separator = sb.toString();
            LOG.info(separator);

            sb.setLength(0);
            sb.append("| Result | Field ");
            for (int i = 6; i < maxNameLength; i++) {
                sb.append(' ');
            }
            sb.append(" | Actual ");
            for (int i = 7; i < maxActualLength; i++) {
                sb.append(' ');
            }
            sb.append(" | Confidence | Expected ");
            for (int i = 9; i < maxExpectedLength; i++) {
                sb.append(' ');
            }
            sb.append(" |");

            LOG.info(sb.toString());

            LOG.info(separator);

            List<String> failedFieldNames = new ArrayList<>();
            for (TestResult result : results) {
                sb.setLength(0);
                if (result.pass) {
                    sb.append("|        | ");
                } else {
                    if (result.warn) {
                        sb.append("| ~warn~ | ");
                    } else {
                        sb.append("| -FAIL- | ");
                        failedFieldNames.add(result.field);
                    }
                }
                sb.append(result.field);
                for (int i = result.field.length(); i < maxNameLength; i++) {
                    sb.append(' ');
                }
                sb.append(" | ");
                sb.append(result.actual);

                for (int i = result.actual.length(); i < maxActualLength; i++) {
                    sb.append(' ');
                }
                sb.append(" | ");
                sb.append(String.format("%10d", result.confidence));
                sb.append(" | ");

                if (result.pass) {
                    for (int i = 0; i < maxExpectedLength; i++) {
                        sb.append(' ');
                    }
                    sb.append(" |");
                    LOG.info(sb.toString());
                } else {
                    sb.append(result.expected);
                    for (int i = result.expected.length(); i < maxExpectedLength; i++) {
                        sb.append(' ');
                    }
                    sb.append(" |");
                    if (result.warn) {
                        LOG.warn(sb.toString());
                    } else {
                        LOG.error(sb.toString());
                    }
                }
            }

            LOG.info(separator);
            LOG.info("");

            LOG.info(agent.toMatchTrace(failedFieldNames));

            LOG.info("\n\nconfig:\n"+agent.toYamlTestCase(!init));
            LOG.info("Location of failed test.({}:{})", filename, linenumber);
            if (!pass && !showAll) {
//                LOG.info("+===========================================================================================");
                return false;
            }
            if (init) {
                return allPass;
            }
        }

        if (showPassedTests) {
            LOG.info("+===========================================================================================");
        }
        return allPass;
    }

    public void preHeat(int preheatIterations) {
        if (!testCases.isEmpty()) {
            if (preheatIterations > 0) {
                LOG.info("Preheating JVM by running {} testcases.", preheatIterations);
                int remainingIterations = preheatIterations;
                int goodResults = 0;
                while (remainingIterations > 0) {
                    for (Map<String, Map<String, String>> test : testCases) {
                        Map<String, String> input = test.get("input");
                        if (input == null) {
                            continue;
                        }

                        String userAgentString = input.get("user_agent_string");
                        if (userAgentString == null) {
                            continue;
                        }
                        remainingIterations--;
                        //Calculate and use result to guarantee not optimized away.
                        if(!parse(userAgentString).hasSyntaxError()) goodResults++;
                        if (remainingIterations <= 0) {
                            break;
                        }
                    }
                }
                LOG.info("Preheating JVM completed. ({} proper results)", preheatIterations, goodResults);
            }
        }
    }

    public static UserAgentAnalyzerTester.Builder newBuilder() {
        return new UserAgentAnalyzerTester.Builder();
    }

    public static class Builder extends UserAgentAnalyzer.Builder {
        private int preheatIterations = 0;

        public Builder() {
            super(builder -> new UserAgentAnalyzerTester("classpath*:UserAgents/**/*.yaml", builder.wantedFieldNames, builder.showMatcherLoadStats));
        }

        @Override
        public UserAgentAnalyzerTester build() {
            UserAgentAnalyzerTester uaa = (UserAgentAnalyzerTester)super.build();
            if (preheatIterations > 0) {
                uaa.preHeat(preheatIterations);
            }

            return uaa;
        }

        public UserAgentAnalyzer.Builder preheat(int iterations) {
            this.preheatIterations = iterations;
            return this;
        }

        @Override
        public UserAgentAnalyzerTester.Builder withField(String fieldName) {
            super.withField(fieldName);
            return this;
        }

        @Override
        public UserAgentAnalyzerTester.Builder withFields(Collection<String> fieldNames) {
            super.withFields(fieldNames);
            return this;
        }

        @Override
        public UserAgentAnalyzerTester.Builder withAllFields() {
            super.withAllFields();
            return this;
        }

        @Override
        public UserAgentAnalyzerTester.Builder showMatcherLoadStats() {
            super.showMatcherLoadStats();
            return this;
        }

        @Override
        public UserAgentAnalyzerTester.Builder hideMatcherLoadStats() {
            super.hideMatcherLoadStats();
            return this;
        }
    }
}