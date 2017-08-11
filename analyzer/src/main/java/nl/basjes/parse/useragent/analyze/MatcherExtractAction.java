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

import java.util.Collection;

public final class MatcherExtractAction extends MatcherAction {

    final String attribute;
    final long confidence;
    final String fixedValue;


    MatcherExtractAction(String attribute, long confidence, String config, WalkList walkList, String fixedValue) {
        super(config, walkList);
        this.attribute = attribute;
        this.confidence = confidence;
        this.fixedValue = fixedValue;
    }

    @Override
    public final String obtainResult(Collection<Match> matches) {
        return fixedValue == null ? processInformedMatches(matches) : fixedValue;
    }

    @Override
    public boolean notValid(Collection<Match> matches) {
        return !usesIsNull && fixedValue == null && matches.isEmpty();
    }

    @Override
    public String toString() {
        return fixedValue == null
            ? "DYNAMIC: (" + attribute + ", " + confidence + "):    " + matchExpression
            : "FIXED  : (" + attribute + ", " + confidence + ") =   \"" + fixedValue + "\"";
    }
}
