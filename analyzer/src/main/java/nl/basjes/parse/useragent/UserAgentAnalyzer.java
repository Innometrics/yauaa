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

package nl.basjes.parse.useragent;

import nl.basjes.parse.useragent.analyze.InvalidParserConfigurationException;
import nl.basjes.parse.useragent.analyze.Matcher;
import nl.basjes.parse.useragent.analyze.MatcherAction;
import nl.basjes.parse.useragent.analyze.WordRangeVisitor.Range;
import nl.basjes.parse.useragent.parse.UserAgentTreeFlattener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import static nl.basjes.parse.useragent.UserAgent.AGENT_NAME;
import static nl.basjes.parse.useragent.UserAgent.AGENT_VERSION;
import static nl.basjes.parse.useragent.UserAgent.AGENT_VERSION_MAJOR;
import static nl.basjes.parse.useragent.UserAgent.DEVICE_BRAND;
import static nl.basjes.parse.useragent.UserAgent.DEVICE_NAME;
import static nl.basjes.parse.useragent.UserAgent.LAYOUT_ENGINE_NAME;
import static nl.basjes.parse.useragent.UserAgent.LAYOUT_ENGINE_VERSION;
import static nl.basjes.parse.useragent.UserAgent.LAYOUT_ENGINE_VERSION_MAJOR;
import static nl.basjes.parse.useragent.UserAgent.OPERATING_SYSTEM_NAME;
import static nl.basjes.parse.useragent.UserAgent.OPERATING_SYSTEM_VERSION;
import static nl.basjes.parse.useragent.UserAgent.PRE_SORTED_FIELDS_LIST;
import static nl.basjes.parse.useragent.UserAgent.SET_ALL_FIELDS;
import static nl.basjes.parse.useragent.UserAgent.SYNTAX_ERROR;
import static nl.basjes.parse.useragent.UserAgent.USERAGENT;

public class UserAgentAnalyzer implements Serializable {
    private static final Logger LOG = LogManager.getLogger(UserAgentAnalyzer.class);
    private static final List<String> HARD_CODED_GENERATED_FIELDS = Arrays.asList(
        SYNTAX_ERROR,
        AGENT_VERSION_MAJOR,
        LAYOUT_ENGINE_VERSION_MAJOR,
        "AgentNameVersion",
        "AgentNameVersionMajor",
        "LayoutEngineNameVersion",
        "LayoutEngineNameVersionMajor",
        "OperatingSystemNameVersion",
        "WebviewAppVersionMajor",
        "WebviewAppNameVersionMajor"
    );

    private final Map<String, Collection<MatcherAction>> informMatcherActions;
    // These are the actual subrange we need for the paths.
    private final Map<String, Set<Range>> informMatcherActionRanges;

    final boolean addUserAgentStr;
    final boolean canDetectHacker;
    final Matcher[] matchers;

    public UserAgentAnalyzer() {
        this("classpath*:UserAgents/**/*.yaml", true);
    }

    public UserAgentAnalyzer(String resourceString, boolean showMatcherStats) {
        this(resourceString, null, showMatcherStats);
    }

    public UserAgentAnalyzer(String resourceString, List<String> wantedFields, boolean showMatcherStats) {
        logVersion();
        ResourceLoader loader= load(resourceString, wantedFields, showMatcherStats);
        informMatcherActions = loader.actions.informMatcherActions;
        informMatcherActionRanges = loader.actions.informMatcherActionRanges;
        matchers = loader.allMatchers.toArray(new Matcher[loader.allMatchers.size()]);
        canDetectHacker = loader.canDetectHacker;
        addUserAgentStr = wantedFields != null && wantedFields.contains(USERAGENT);

        verifyWeAreNotAskingForImpossibleFields(wantedFields);
    }

    protected ResourceLoader load(String resourceString, List<String> wantedFields, boolean showMatcherStats) {
        return new ResourceLoader(resourceString, wantedFields, showMatcherStats);
    }

    private void verifyWeAreNotAskingForImpossibleFields(List<String> wantedFieldNames) {
        if (wantedFieldNames == null) {
            return; // Nothing to check
        }
        List<String> impossibleFields = new ArrayList<>();
        List<String> allPossibleFields = getAllPossibleFieldNamesSorted();

        for (String wantedFieldName: wantedFieldNames) {
            if (UserAgent.isSystemField(wantedFieldName)) {
                continue; // These are fine
            }
            if (!allPossibleFields.contains(wantedFieldName)) {
                impossibleFields.add(wantedFieldName);
            }
        }
        if (impossibleFields.isEmpty()) {
            return;
        }
        throw new InvalidParserConfigurationException("We cannot provide these fields:" + impossibleFields.toString());
    }


    public static void logVersion(String... extraLines) {
        String[] lines = {
            "For more information: https://github.com/nielsbasjes/yauaa",
            "Copyright (C) 2013-2017 Niels Basjes - License Apache 2.0"
        };
        String version = getVersion();
        int width = version.length();
        for (String line : lines) {
            width = Math.max(width, line.length());
        }
        for (String line : extraLines) {
            width = Math.max(width, line.length());
        }

        LOG.info("");
        LOG.info("/-{}-\\", padding('-', width));
        logLine(version, width);
        LOG.info("+-{}-+", padding('-', width));
        for (String line : lines) {
            logLine(line, width);
        }
        if (extraLines.length > 0) {
            LOG.info("+-{}-+", padding('-', width));
            for (String line : extraLines) {
                logLine(line, width);
            }
        }

        LOG.info("\\-{}-/", padding('-', width));
        LOG.info("");
    }

    private static String padding(char letter, int count) {
        StringBuilder sb = new StringBuilder(128);
        for (int i = 0; i < count; i++) {
            sb.append(letter);
        }
        return sb.toString();
    }

    private static void logLine(String line, int width) {
        LOG.info("| {}{} |", line, padding(' ', width - line.length()));
    }

    // --------------------------------------------


    public static String getVersion() {
        return "Yauaa " + Version.getProjectVersion() + " (" + Version.getGitCommitIdDescribeShort() + " @ " + Version.getBuildTimestamp() + ")";
    }

    Set<String> getAllPossibleFieldNames() {
        Set<String> results = new TreeSet<>();
        results.addAll(HARD_CODED_GENERATED_FIELDS);
        for (Matcher matcher : matchers) {
            results.addAll(matcher.getAllPossibleFieldNames());
        }
        return results;
    }

    public List<String> getAllPossibleFieldNamesSorted() {
        List<String> fieldNames = new ArrayList<>(getAllPossibleFieldNames());
        Collections.sort(fieldNames);

        List<String> result = new ArrayList<>();
        for (String fieldName : PRE_SORTED_FIELDS_LIST) {
            fieldNames.remove(fieldName);
            result.add(fieldName);
        }
        result.addAll(fieldNames);

        return result;
    }

/*
Example of the structure of the yaml file:
----------------------------
config:
  - lookup:
    name: 'lookupname'
    map:
        "From1" : "To1"
        "From2" : "To2"
        "From3" : "To3"
  - matcher:
      options:
        - 'verbose'
        - 'init'
      require:
        - 'Require pattern'
        - 'Require pattern'
      extract:
        - 'Extract pattern'
        - 'Extract pattern'
  - test:
      input:
        user_agent_string: 'Useragent'
      expected:
        FieldName     : 'ExpectedValue'
        FieldName     : 'ExpectedValue'
        FieldName     : 'ExpectedValue'
----------------------------
*/


    protected UserAgent createUserAgent(String userAgentString) {
        return new UserAgent((userAgentString));
    }

    protected final Map<MatcherAction, Collection<MatcherAction.Match>> _matches(UserAgent userAgent) {
        Map<MatcherAction, Collection<MatcherAction.Match>> matches = new HashMap<>();
        UserAgentTreeFlattener.parse(userAgent, informMatcherActionRanges, (path, value, ctx) -> {
            MatcherAction.Match match = new MatcherAction.Match(path, value, ctx);
            String lpath = path.toLowerCase(Locale.ENGLISH);
            informMatcherActions.getOrDefault(lpath, Collections.emptySet()).forEach(
                action -> matches.computeIfAbsent(action, k -> new ArrayDeque<>()).add(match)
            );

            lpath += "=\"" + (value == null ? null : value.toLowerCase(Locale.ENGLISH)) + '"';
            informMatcherActions.getOrDefault(lpath, Collections.emptySet()).forEach(
                action -> matches.computeIfAbsent(action, k -> new ArrayDeque<>()).add(match)
            );
        });
        return matches;
    }

    protected final void _parse(UserAgent userAgent, Map<MatcherAction, Collection<MatcherAction.Match>> matches) {
        Arrays.stream(matchers).forEach( matcher -> matcher.analyze(userAgent.withMatcher(matcher), matches));
        // Fire all Analyzers
        userAgent.processSetAll();
        userAgent.hardCodedPostProcessing(addUserAgentStr, canDetectHacker);
    }

    public UserAgent parse(String userAgentString) {
        UserAgent userAgent = createUserAgent(userAgentString);
        _parse(userAgent, _matches(userAgent));
        return userAgent;
    }

    // ===============================================================================================================

    @SuppressWarnings({"unused"})
    public static Collection<String> getAllPaths(String agent) {
        final Collection<String> values = new ArrayDeque<>(128);
        UserAgentTreeFlattener.parse(new UserAgent(agent), Collections.emptyMap(), (path, value, ctx) -> {
            values.add(path);
            values.add(path + "=\"" + value + "\"");
        });
        return values;
    }

    // ===============================================================================================================

    public static Builder newBuilder() {
        return new Builder();
    }

    @SuppressWarnings({"UnusedReturnValue", "unused"})
    public static class Builder {
        private final Function<Builder, UserAgentAnalyzer> buildFunction;
        // If we want ALL fields this is null. If we only want specific fields this is a list of names.
        public List<String> wantedFieldNames = null;
        public boolean showMatcherLoadStats = true;

        protected Builder() {
            this( builder -> new UserAgentAnalyzer("classpath*:UserAgents/**/*.yaml", builder.wantedFieldNames, builder.showMatcherLoadStats));
        }

        public Builder(Function<Builder, UserAgentAnalyzer> buildFunction) {
            this.buildFunction = buildFunction;
        }

        public Builder withField(String fieldName) {
            if (wantedFieldNames == null) {
                wantedFieldNames = new ArrayList<>(32);
            }
            wantedFieldNames.add(fieldName);
            return this;
        }

        public Builder withFields(Collection<String> fieldNames) {
            if (fieldNames == null) {
                return this;
            }
            for (String fieldName : fieldNames) {
                withField(fieldName);
            }
            return this;
        }

        public Builder withAllFields() {
            wantedFieldNames = null;
            return this;
        }

        public Builder showMatcherLoadStats() {
            showMatcherLoadStats = true;
            return this;
        }

        public Builder hideMatcherLoadStats() {
            showMatcherLoadStats = false;
            return this;
        }

        private void addGeneratedFields(String result, String... dependencies) {
            if (wantedFieldNames.contains(result)) {
                Collections.addAll(wantedFieldNames, dependencies);
            }
        }

        public UserAgentAnalyzer build() {
            if (wantedFieldNames != null) {
                addGeneratedFields("AgentNameVersion", AGENT_NAME, AGENT_VERSION);
                addGeneratedFields("AgentNameVersionMajor", AGENT_NAME, AGENT_VERSION_MAJOR);
                addGeneratedFields("WebviewAppNameVersionMajor", "WebviewAppName", "WebviewAppVersionMajor");
                addGeneratedFields("LayoutEngineNameVersion", LAYOUT_ENGINE_NAME, LAYOUT_ENGINE_VERSION);
                addGeneratedFields("LayoutEngineNameVersionMajor", LAYOUT_ENGINE_NAME, LAYOUT_ENGINE_VERSION_MAJOR);
                addGeneratedFields("OperatingSystemNameVersion", OPERATING_SYSTEM_NAME, OPERATING_SYSTEM_VERSION);
                addGeneratedFields(DEVICE_NAME, DEVICE_BRAND);
                addGeneratedFields(AGENT_VERSION_MAJOR, AGENT_VERSION);
                addGeneratedFields(LAYOUT_ENGINE_VERSION_MAJOR, LAYOUT_ENGINE_VERSION);
                addGeneratedFields("WebviewAppVersionMajor", "WebviewAppVersion");

                // Special field that affects ALL fields.
                wantedFieldNames.add(SET_ALL_FIELDS);
            }
            return buildFunction.apply(this);
        }
    }
}
