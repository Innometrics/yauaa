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

public final class MatcherRequireAction extends MatcherAction {

    MatcherRequireAction(String matchExpression, WalkList walkList) {
        super(matchExpression, walkList);
    }

    @Override
    public String obtainResult(Collection<Match> matches) {
        return (usesIsNull && matches.isEmpty()) || processInformedMatches(matches) != null  ? "": null;
    }

    @Override
    public boolean notValid(Collection<Match> matches) {
        return !usesIsNull && matches.isEmpty();
    }

    @Override
    public String toString() {
        return "Require: " + matchExpression;
    }
}
