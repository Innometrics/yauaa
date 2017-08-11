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

import nl.basjes.parse.useragent.analyze.ActionBuilder;
import nl.basjes.parse.useragent.analyze.InvalidParserConfigurationException;
import nl.basjes.parse.useragent.analyze.Matcher;
import nl.basjes.parse.useragent.analyze.UselessMatcherException;
import nl.basjes.parse.useragent.utils.YamlUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.reader.UnicodeReader;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static nl.basjes.parse.useragent.UserAgent.DEVICE_CLASS;
import static nl.basjes.parse.useragent.UserAgent.LAYOUT_ENGINE_CLASS;
import static nl.basjes.parse.useragent.UserAgent.OPERATING_SYSTEM_CLASS;
import static nl.basjes.parse.useragent.utils.YamlUtils.*;

public class ResourceLoader {
    private static final Logger LOG = LogManager.getLogger(ResourceLoader.class);

    private boolean doingOnlyASingleTest = false;
    final ActionBuilder actions = new ActionBuilder();
    final List<Matcher> allMatchers = new ArrayList<>();
    final boolean canDetectHacker;

    final Map<String, List<MappingNode>> matcherConfigs = new HashMap<>(64);

    public final List<Map<String, Map<String, String>>> testCases = new ArrayList<>(2048);

    public ResourceLoader(String resourceString, List<String> wantedFieldNames, boolean showMatcherStats) {
        LOG.info("Loading from: \"{}\"", resourceString);

        canDetectHacker = wantedFieldNames == null ||
            wantedFieldNames.contains(DEVICE_CLASS) ||
            wantedFieldNames.contains(OPERATING_SYSTEM_CLASS) ||
            wantedFieldNames.contains(LAYOUT_ENGINE_CLASS);

        Map<String, Resource> resources = new TreeMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resourceArray = resolver.getResources(resourceString);
            for (Resource resource : resourceArray) {
                resources.put(resource.getFilename(), resource);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        int maxFilenameLength = 0;

        if (resources.isEmpty()) {
            throw new InvalidParserConfigurationException("Unable to find ANY config files");
        }

        Yaml yaml = new Yaml();
        for (Map.Entry<String, Resource> resourceEntry : resources.entrySet()) {
            try {
                Resource resource = resourceEntry.getValue();
                String filename = resource.getFilename();
                maxFilenameLength = Math.max(maxFilenameLength, filename.length());
                try (InputStream stream = resource.getInputStream()) {
                    loadResource(yaml.compose(new UnicodeReader(stream)), filename);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        LOG.info("Loaded {} files", resources.size());

        LOG.info("Building all matchers");
        int totalNumberOfMatchers = 0;
        int skippedMatchers = 0;
        long fullStart = System.nanoTime();
        for (Map.Entry<String, Resource> resourceEntry : resources.entrySet()) {
            Resource resource = resourceEntry.getValue();
            String configFilename = resource.getFilename();
            List<MappingNode> matcherConfig = matcherConfigs.get(configFilename);
            if (matcherConfig == null) {
                continue; // No matchers in this file (probably only lookups and/or tests)
            }

            long start = System.nanoTime();
            int startSize = actions.informMatcherActions.size();
            for (MappingNode map : matcherConfig) {
                try {
                    allMatchers.add(new Matcher(actions, wantedFieldNames, map, configFilename));
                    totalNumberOfMatchers++;
                } catch (UselessMatcherException ume) {
                    skippedMatchers++;
                }
            }
            long stop = System.nanoTime();
            int stopSize = actions.informMatcherActions.size();

            if (showMatcherStats) {
                Formatter msg = new Formatter(Locale.ENGLISH);
                msg.format("Building %4d matchers from %-" + maxFilenameLength + "s took %5d msec resulted in %8d extra hashmap entries",
                    matcherConfig.size(),
                    configFilename,
                    (stop - start) / 1000000,
                    stopSize - startSize);
                LOG.info(msg.toString());
            }
        }
        long fullStop = System.nanoTime();

        Formatter msg = new Formatter(Locale.ENGLISH);
        msg.format("Building %4d (dropped %4d) matchers from %4d files took %5d msec",
            totalNumberOfMatchers,
            skippedMatchers,
            matcherConfigs.size(),
            (fullStop - fullStart) / 1000000);
        LOG.info(msg.toString());

        LOG.info("Analyzer stats");
        LOG.info("Lookups      : {}", actions.getLookupSize());
        LOG.info("Matchers     : {} (total:{} ; dropped: {})", allMatchers.size(), totalNumberOfMatchers, skippedMatchers);
        LOG.info("Hashmap size: {}", actions.informMatcherActions.size());
        LOG.info("Ranges map size : {}", actions.informMatcherActionRanges.size());
        LOG.info("Testcases    : {}", testCases.size());
    }

    private void loadResource(Node loadedYaml, String filename) {

        if (loadedYaml == null) {
            throw new InvalidParserConfigurationException("The file " + filename + " is empty");
        }

        // Get and check top level config
        if (!(loadedYaml instanceof MappingNode)) {
            fail(loadedYaml, filename, "File must be a Map");
        }

        MappingNode rootNode = (MappingNode) loadedYaml;

        NodeTuple configNodeTuple = null;
        for (NodeTuple tuple : rootNode.getValue()) {
            String name = getKeyAsString(tuple, filename);
            if ("config".equals(name)) {
                configNodeTuple = tuple;
                break;
            }
        }

        if (configNodeTuple == null) {
            fail(loadedYaml, filename, "The top level entry MUST be 'config'.");
        }

        SequenceNode configNode = getValueAsSequenceNode(configNodeTuple, filename);
        List<Node> configList = configNode.getValue();

        for (Node configEntry : configList) {
            if (!(configEntry instanceof MappingNode)) {
                fail(loadedYaml, filename, "The entry MUST be a mapping");
            }

            NodeTuple entry = getExactlyOneNodeTuple((MappingNode) configEntry, filename);
            MappingNode actualEntry = getValueAsMappingNode(entry, filename);
            String entryType = getKeyAsString(entry, filename);
            switch (entryType) {
                case "lookup":
                    loadYamlLookup(actualEntry, filename);
                    break;
                case "matcher":
                    loadYamlMatcher(actualEntry, filename);
                    break;
                case "test":
                    loadYamlTestcase(actualEntry, filename);
                    break;
                default:
                    throw new InvalidParserConfigurationException(
                        "Yaml config.(" + filename + ":" + actualEntry.getStartMark().getLine() + "): " +
                            "Found unexpected config entry: " + entryType + ", allowed are 'lookup, 'matcher' and 'test'");
            }
        }
    }

    private void loadYamlLookup(MappingNode entry, String filename) {
        String name = null;
        ArrayList<NodeTuple> tuples = new ArrayList<>();
        for (NodeTuple tuple : entry.getValue()) {
            switch (getKeyAsString(tuple, filename)) {
                case "name":
                    name = getValueAsString(tuple, filename);
                    break;
                case "map":
                    tuples.addAll(getValueAsMappingNode(tuple, filename).getValue());
                    break;
                default:
                    break;
            }
        }

        if (name == null && tuples.isEmpty()) {
            fail(entry, filename, "Invalid lookup specified");
        }

        Map<String, String> map = new HashMap<>(tuples.size());
        for (NodeTuple mapping : tuples)
            map.put(getKeyAsString(mapping, filename).toLowerCase().intern(), getValueAsString(mapping, filename));

        actions.addLookup(name, map);
    }

    private void loadYamlMatcher(MappingNode entry, String filename) {
        matcherConfigs.computeIfAbsent(filename, k -> new ArrayList<>(32)).add(entry);
    }

    private void loadYamlTestcase(MappingNode entry, String filename) {
        if (!doingOnlyASingleTest) {
            Map<String, String> metaData = new HashMap<>();
            metaData.put("filename", filename);
            metaData.put("fileline", String.valueOf(entry.getStartMark().getLine()));

            String inputString = null;
            List<String> options = null;
            Map<String, String> expected = null;
            for (NodeTuple tuple : entry.getValue()) {
                String name = getKeyAsString(tuple, filename);
                switch (name) {
                    case "options":
                        options = YamlUtils.getStringValues(tuple.getValueNode(), filename);
                        if (options != null) {
                            if (options.contains("only")) {
                                doingOnlyASingleTest = true;
                                testCases.clear();
                            }
                        }
                        break;
                    case "input":
                        for (NodeTuple inputTuple : getValueAsMappingNode(tuple, filename).getValue()) {
                            String inputName = getKeyAsString(inputTuple, filename);
                            if("user_agent_string".equals(inputName)) {
                                inputString = getValueAsString(inputTuple, filename);
                            }
                        }
                        break;
                    case "expected":
                        List<NodeTuple> mappings = getValueAsMappingNode(tuple, filename).getValue();
                        if (mappings != null) {
                            if (expected == null) {
                                expected = new HashMap<>();
                            }
                            for (NodeTuple mapping : mappings) {
                                String key = getKeyAsString(mapping, filename);
                                String value = getValueAsString(mapping, filename);
                                expected.put(key, value);
                            }
                        }
                        break;
                    default:
//                        fail(tuple.getKeyNode(), filename, "Unexpected: " + name);
                        break; // Skip
                }
            }

            if (inputString== null) {
                fail(entry, filename, "Test is missing input");
            }

            if (expected == null || expected.isEmpty()) {
                doingOnlyASingleTest = true;
                testCases.clear();
            }

            Map<String, Map<String, String>> testCase = new HashMap<>();

            testCase.put("input", Collections.singletonMap("user_agent_string", inputString));
            if (expected != null) {
                testCase.put("expected", expected);
            }
            if (options != null) {
                Map<String, String> optionsMap = new HashMap<>(options.size());
                for (String option: options) {
                    optionsMap.put(option, option);
                }
                testCase.put("options", optionsMap);
            }
            testCase.put("metaData", metaData);
            testCases.add(testCase);
        }
    }
}
