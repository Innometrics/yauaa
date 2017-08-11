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

import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.analyze.Matcher;
import nl.basjes.parse.useragent.analyze.FieldSetter;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.stream.Stream;

public class DebugUserAgent extends UserAgent {

    private static final Logger LOG = LogManager.getLogger(DebugUserAgent.class);

    private final ArrayDeque<ImmutablePair<Matcher, Collection<AgentField>>> appliedMatcherResults = new ArrayDeque<>(32);

    public DebugUserAgent(String userAgentString) {
        super(userAgentString);
    }

    @Override
    public FieldSetter withMatcher(Matcher matcher) {
        return values -> {
            set(values);
            appliedMatcherResults.add(new ImmutablePair<>(matcher, values));
        };
    }

    public long getNumberOfAppliedMatches() {
        return appliedMatcherResults.stream().filter(pair -> !pair.getRight().isEmpty()).count();
    }

    public String toMatchTrace(List<String> highlightNames) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("\n");
        sb.append("+=========================================+\n");
        sb.append("| Matcher results that have been combined |\n");
        sb.append("+=========================================+\n");
        sb.append("\n");
        appliedMatcherResults.stream().filter(pair -> !pair.getRight().isEmpty()).forEach( pair -> {
            sb.append("\n");
            sb.append("+================\n");
            sb.append("+ Applied matched\n");
            sb.append("+----------------\n");
            Matcher matcher = pair.getLeft();
            Collection<AgentField> values = pair.getRight();
            sb.append(matcher.toString());
            sb.append("+----------------\n");
            sb.append("+ Results\n");
            sb.append("+----------------\n");

            for (String fieldName : getAvailableFieldNamesSorted()) {
                Optional<AgentField> field = values.stream().filter(value -> value.attribute.equals(fieldName)).findFirst();
                if (field.isPresent() && field.get().confidence >= 0) {
                    String marker = "";
                    if (highlightNames.contains(fieldName)) {
                        marker = " <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<";
                    }
                    sb.append("| ").append(fieldName).append('(').append(field.get().confidence).append(") = ")
                        .append(field.get().value).append(marker).append('\n');
                }
            }
            sb.append("+================\n");

            }
        );
        return sb.toString();
    }

    public Stream<Matcher> usedMatchers() {
        return appliedMatcherResults.stream().map(ImmutablePair::getLeft);
    }

    public boolean analyzeMatchersResult() {
        final boolean[] passed = {true};
        for (String fieldName : getAvailableFieldNamesSorted()) {
            Map<Long, String> receivedValues = new HashMap<>(32);
            appliedMatcherResults.stream().filter(pair -> !pair.getRight().isEmpty()).forEach(pair -> {
                Collection<AgentField> values = pair.getRight();
                Optional<AgentField> partialField = values.stream().filter(value -> value.attribute.equals(fieldName)).findFirst();
                if (partialField.isPresent() && partialField.get().confidence >= 0) {
                    String previousValue = receivedValues.get(partialField.get().confidence);
                    if (previousValue != null) {
                        if (!previousValue.equals(partialField.get().value)) {
                            if (passed[0]) {
                                LOG.error("***********************************************************");
                                LOG.error("***        REALLY IMPORTANT ERRORS IN THE RULESET       ***");
                                LOG.error("*** YOU MUST CHANGE THE CONFIDENCE LEVELS OF YOUR RULES ***");
                                LOG.error("***********************************************************");
                            }
                            passed[0] = false;
                            LOG.error("Found different value for \"{}\" with SAME confidence {}: \"{}\" and \"{}\"",
                                fieldName, partialField.get().confidence, previousValue, partialField.get().value);
                        }
                    } else {
                        receivedValues.put(partialField.get().confidence, partialField.get().value);
                    }
                }

            });
        }
        return passed[0];
    }
}
