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


import nl.basjes.parse.useragent.analyze.treewalker.steps.WalkList;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Serializable;
import java.util.Collection;

public abstract class MatcherAction implements Serializable {

    final String matchExpression;
    final WalkList walkList;

    private static final Logger LOG = LogManager.getLogger(MatcherAction.class);

    public static class Match {
        public final String key;
        public final String value;
        public final ParseTree result;

        public Match(String key, String value, ParseTree result) {
            this.key = key;
            this.value = value;
            this.result = result;
        }
    }

    final boolean usesIsNull;

    MatcherAction(String matchExpression, WalkList walkList) {
        this.matchExpression = matchExpression;
        this.walkList = walkList;
        usesIsNull = walkList.usesIsNull();
    }


    /**
     * @return If it is impossible that this can be valid it returns true, else false.
     */
    public abstract boolean notValid(Collection<Match> matches);

    /**
     * Called after all nodes have been notified.
     *
     * @return the value if the obtainResult result was valid. null will fail the entire matcher this belongs to.
     */
    public abstract String obtainResult(Collection<Match> matches);

    /**
     * Optimization: Only if there is a possibility that all actions for this matcher CAN be valid do we
     * actually perform the analysis and do the (expensive) tree walking and matching.
     */
    String processInformedMatches(Collection<Match> matches) {
        for (Match match : matches) {
            String matchedValue = evaluate(match.result, match.key, match.value);
            if (matchedValue != null) return matchedValue;
        }
        return null;
    }

    private String evaluate(ParseTree tree, String key, String value) {
        if(!LOG.isDebugEnabled())
            return walkList.walk(tree, value);

        LOG.debug("Evaluate: {} => {}", key, value);
        //LOG.debug("Pattern : {}", requiredPattern.getText());
        LOG.debug("WalkList: {}", walkList.toString());
        String result = walkList.walk(tree, value);
        LOG.debug("Evaluate: Result = {}", result);
        return result;
    }
}
