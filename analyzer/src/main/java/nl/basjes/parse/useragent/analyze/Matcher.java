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

package nl.basjes.parse.useragent.analyze;

import nl.basjes.parse.useragent.analyze.FieldSetter.AgentField;
import nl.basjes.parse.useragent.utils.YamlUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.NodeTuple;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static java.util.Collections.emptyList;
import static nl.basjes.parse.useragent.UserAgent.SET_ALL_FIELDS;
import static nl.basjes.parse.useragent.utils.YamlUtils.getKeyAsString;

public class Matcher implements Serializable {
    private static final Logger LOG = LogManager.getLogger(Matcher.class);

    private static final Collection<MatcherAction.Match> EMPTY_FALLBACK = emptyList();

    public final MatcherAction[] dynamicActions;
    private final AgentField[] fixedValues;

    // Used for error reporting: The filename where the config was located.
    private final String filename;

    private final boolean verbose;

    private static class ConfigLine {
        String attribute;
        Long confidence;
        String expression;

        ConfigLine(String attribute, Long confidence, String expression) {
            this.attribute = attribute;
            this.confidence = confidence;
            this.expression = expression;
        }
    }

    public Matcher(ActionBuilder actions,
                   List<String> wantedFieldNames,
                   MappingNode matcherConfig,
                   String filename) throws UselessMatcherException {

        this.filename = filename + ':' + matcherConfig.getStartMark().getLine();

        boolean isVerbose = false;
        boolean hasActiveExtractConfigs = false;
        boolean hasDefinedExtractConfigs = false;
        boolean allFields = wantedFieldNames == null || wantedFieldNames.isEmpty();

        // List of 'attribute', 'confidence', 'expression'
        ArrayDeque<ConfigLine> configLines = new ArrayDeque<>(16);
        for (NodeTuple nodeTuple: matcherConfig.getValue()) {
            String name = getKeyAsString(nodeTuple, filename);
            switch (name) {
                case "options":
                    List<String> options = YamlUtils.getStringValues(nodeTuple.getValueNode(), filename);
                    isVerbose = options != null && options.contains("verbose");
                    break;
                case "require":
                    for (String requireConfig : YamlUtils.getStringValues(nodeTuple.getValueNode(), filename)) {
                        configLines.add(new ConfigLine(null, null, requireConfig));
                    }
                    break;
                case "extract":
                    for (String extractConfig : YamlUtils.getStringValues(nodeTuple.getValueNode(), filename)) {
                        String[] configParts = extractConfig.split(":", 3);

                        if (configParts.length != 3) {
                            throw new InvalidParserConfigurationException("Invalid extract config line: " + extractConfig);
                        }
                        String attribute = configParts[0].trim();
                        Long confidence = Long.parseLong(configParts[1].trim());
                        String config = configParts[2].trim();

                        hasDefinedExtractConfigs = true;
                        // If we have a restriction on the wanted fields we check if this one is needed at all
                        if (allFields || wantedFieldNames.contains(attribute)) {
                            configLines.add(new ConfigLine(attribute, confidence, config));
                            hasActiveExtractConfigs = true;
                        } else {
                            configLines.add(new ConfigLine(null, null, config));
                        }
                    }
                    break;
                default:
                    // Ignore
//                    fail(nodeTuple.getKeyNode(), filename, "Unexpected " + name);
            }
        }

        verbose = isVerbose || LOG.isDebugEnabled();
        if (verbose) {
            LOG.info("---------------------------");
            LOG.info("- MATCHER -");

        }

        if (!hasDefinedExtractConfigs) {
            throw new InvalidParserConfigurationException("Matcher does not extract anything");
        }

        if (!hasActiveExtractConfigs) {
            throw new UselessMatcherException("Does not extract any wanted fields");
        }

        ArrayDeque<MatcherAction> tmpdynamicActions = new ArrayDeque<>();
        ArrayDeque<AgentField> tmpFixedValues = new ArrayDeque<>();

        for (ConfigLine configLine : configLines) {
            if (configLine.attribute == null) {
                // Require
                if (verbose) {
                    LOG.info("REQUIRE: {}", configLine.expression);
                }
                try {
                    tmpdynamicActions.add(actions.requireAction(configLine.expression));
                } catch (InvalidParserConfigurationException e) {
                    if (!e.getMessage().startsWith("It is useless to put a fixed value")) {// Ignore fixed values in require
                        throw e;
                    }
                }
            } else {
                // Extract
                if (verbose) {
                    LOG.info("EXTRACT: {}", configLine.expression);
                }
                MatcherExtractAction action =
                    actions.extractAction(configLine.attribute, configLine.confidence, configLine.expression);

                if (action.fixedValue == null) {
                    tmpdynamicActions.add(action);
                } else {
                    tmpFixedValues.add(new AgentField(action.attribute, action.fixedValue, action.confidence));
                }
            }
        }

        dynamicActions = tmpdynamicActions.toArray(new MatcherAction[tmpdynamicActions.size()]);
        fixedValues = tmpFixedValues.toArray(new AgentField[tmpFixedValues.size()]);
        if (verbose) LOG.info("---------------------------");
    }

    public Set<String> getAllPossibleFieldNames() {
        Set<String> results = new TreeSet<>();
        results.addAll(getAllPossibleFieldNames(dynamicActions));
        for (AgentField fixedValue : fixedValues) {
            results.add(fixedValue.attribute);
        }
        results.remove(SET_ALL_FIELDS);
        return results;
    }

    private Set<String> getAllPossibleFieldNames(MatcherAction[] actions) {
        Set<String> results = new TreeSet<>();
        for (MatcherAction action: actions) {
            if (action instanceof MatcherExtractAction) {
                MatcherExtractAction extractAction = (MatcherExtractAction)action;
                results.add(extractAction.attribute);
            }
        }
        return results;
    }

    /**
     * Fires all matcher actions.
     * IFF all success then we tell the userAgent
     *
     * @param setter set matches will be set here.
     */
    public final void analyze(FieldSetter setter, Map<MatcherAction, Collection<MatcherAction.Match>> matches) {
        if (isVerbose()) {
            analyzeWithLogging(setter, matches);
            return;
        }

        for (MatcherAction action : dynamicActions) {
            if (action.notValid(matches.getOrDefault(action, EMPTY_FALLBACK))) return;
        }

        ArrayDeque<AgentField> values = new ArrayDeque<>();
        for (MatcherAction action : dynamicActions) {
            String value = action.obtainResult(matches.getOrDefault(action, EMPTY_FALLBACK));
            if (value == null) return; // If one of them is bad we skip the rest
            if(action instanceof MatcherExtractAction) {
                MatcherExtractAction me = (MatcherExtractAction) action;
                values.add(new AgentField(me.attribute, value, me.confidence));
            }
        }

        Collections.addAll(values, fixedValues);
        setter.set(values);
    }

    private boolean isVerbose() {
        return verbose || LOG.isDebugEnabled();
    }

    private void analyzeWithLogging(FieldSetter setter, Map<MatcherAction, Collection<MatcherAction.Match>> matches) {
        boolean failing = false;
        for (MatcherAction action : dynamicActions) {
            if (action.notValid(matches.getOrDefault(action, EMPTY_FALLBACK))) {
                failing = true;
                LOG.error("CANNOT BE VALID : {}", action.matchExpression);
            }
        }

        ArrayDeque<AgentField> values = new ArrayDeque<>();
        for (MatcherAction action : dynamicActions) {
            String value = action.obtainResult(matches.getOrDefault(action, EMPTY_FALLBACK));
            if (value == null) {
                LOG.error("FAILED : {}", action.matchExpression);
                failing = true;
            }
            if (!failing && action instanceof MatcherExtractAction) {
                MatcherExtractAction me = (MatcherExtractAction) action;
                values.add(new AgentField(me.attribute, value, me.confidence));
            }
        }
        if (failing) {
            LOG.info("INCOMPLETE ----------------------------");
            return;
        }
        Collections.addAll(values, fixedValues);
        LOG.info("COMPLETE ----------------------------");
        setter.set(values);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("MATCHER.(").append(filename).append("):\n");
        sb.append("    REQUIRE:\n");
        for (MatcherAction action : dynamicActions) {
            if (action instanceof MatcherRequireAction) {
                sb.append("        ").append(action.matchExpression).append("\n");
            }
        }
        sb.append("    EXTRACT:\n");
        for (MatcherAction action : dynamicActions) {
            if (action instanceof MatcherExtractAction) {
                sb.append("        ").append(action.toString()).append("\n");
            }
        }
        for (AgentField f : fixedValues) {
            sb.append("        ").append("FIXED  : (");
            sb.append(f.attribute).append(", ").append(f.confidence).append(") =   \"").append(f.value).append("\"\n");
        }
        return sb.toString();
    }
}
