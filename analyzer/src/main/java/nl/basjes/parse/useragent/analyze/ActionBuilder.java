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
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerBaseVisitor;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerLexer;
import nl.basjes.parse.useragent.parser.UserAgentTreeWalkerParser;
import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.dfa.DFA;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static nl.basjes.parse.useragent.analyze.NumberRangeVisitor.NUMBER_RANGE_VISITOR;

public class ActionBuilder {
    private static final Logger LOG = LogManager.getLogger(ActionBuilder.class);

    private static final String AGENT = "agent";

    private final Map<String, Map<String, String>> lookups = new HashMap<>(128);


    public Map<String, Collection<MatcherAction>> informMatcherActions = new HashMap<>();
    public Map<String, Set<WordRangeVisitor.Range>> informMatcherActionRanges = new HashMap<>();

    public int getLookupSize() {
        return lookups.size();
    }

    public void addLookup(String name, Map<String, String> map) {
        lookups.put(name, map);
    }

    interface Build<T> {
        T build(String matchExpression, WalkList walkList, String fixedValue);
    }

    private <T extends MatcherAction> T build(String matchExpression, ParserRuleContext pattern, Build<T> buildFunc) {
        WalkList walkList = new WalkList(pattern, lookups);
        String fixedValue = isFixedValue(pattern, lookups);
        T action =  buildFunc.build(matchExpression, walkList, fixedValue);
        if (fixedValue == null) registerPattern(pattern, action);
        return action;
    }

    MatcherRequireAction requireAction(String matchExpression) {
        ParserRuleContext pattern = getRequiredPattern(matchExpression, UserAgentTreeWalkerParser::matcherRequire);
        return build(matchExpression, pattern, (me, p, fixedValue) -> {
            if(fixedValue != null) {
                throw new InvalidParserConfigurationException(
                    "It is useless to put a fixed value \"" + fixedValue + "\" in the require section.");
            }

            return new MatcherRequireAction(me,p);
        });
    }


    MatcherExtractAction extractAction(String attribute, long confidence, String matchExpression) {
        ParserRuleContext pattern = getRequiredPattern(matchExpression, UserAgentTreeWalkerParser::matcher);
        return build(matchExpression, pattern, (e, w, f) -> new MatcherExtractAction(attribute, confidence, e, w, f));
    }

    private void registerPattern(ParserRuleContext pattern, MatcherAction action) {
        if (pattern instanceof UserAgentTreeWalkerParser.MatcherBaseContext) {
            calculateMatcherPath(action, ((UserAgentTreeWalkerParser.MatcherBaseContext) pattern).matcher());
        } else  if (pattern instanceof UserAgentTreeWalkerParser.MatcherPathIsNullContext) {
            calculateMatcherPath(action, ((UserAgentTreeWalkerParser.MatcherPathIsNullContext) pattern).matcher());
        } else if (pattern instanceof UserAgentTreeWalkerParser.MatcherContext) {
            calculateMatcherPath(action, ((UserAgentTreeWalkerParser.MatcherContext) pattern));
        }
    }

    protected void informMatcherAbout(String pattern, MatcherAction action) {
        informMatcherActions.computeIfAbsent(pattern.toLowerCase(), k -> new ArrayDeque<>()).add(action);
    }

    protected void lookingForRange(String pattern, WordRangeVisitor.Range range) {
        informMatcherActionRanges.computeIfAbsent(pattern, k -> new HashSet<>(4)).add(range);
    }

    private void calculateMatcherPath(MatcherAction action, UserAgentTreeWalkerParser.MatcherContext tree) {
        for (; tree != null; tree = getMatcher(tree)) {
            if (tree instanceof UserAgentTreeWalkerParser.MatcherPathContext) {
                calculateBasePath(action, ((UserAgentTreeWalkerParser.MatcherPathContext) tree).basePath());
                return;
            }
        }
    }

    private void calculateBasePath(MatcherAction action, UserAgentTreeWalkerParser.BasePathContext tree) {
        // Useless to register a fixed value
//             case "PathFixedValueContext"         : calculateInformPath(treeName, (PathFixedValueContext)         tree); break;
        if (tree instanceof UserAgentTreeWalkerParser.PathWalkContext) {
            calculateInformPath(action, AGENT, ((UserAgentTreeWalkerParser.PathWalkContext) tree).nextStep);
        }
    }

    private void calculateInformPath(MatcherAction action,  String treeName, UserAgentTreeWalkerParser.PathContext tree) {
        if (tree != null) {
            if (tree instanceof UserAgentTreeWalkerParser.StepDownContext){
                calculateStepDownPath(action, treeName, (UserAgentTreeWalkerParser.StepDownContext) tree);
                return;
            }
            if (tree instanceof UserAgentTreeWalkerParser.StepEqualsValueContext){
                calculateStepEqualsValuePath(action, treeName, (UserAgentTreeWalkerParser.StepEqualsValueContext) tree);
                return;
            }
            if (tree instanceof UserAgentTreeWalkerParser.StepWordRangeContext) {
                calculateStepWordRangePath(action, treeName, (UserAgentTreeWalkerParser.StepWordRangeContext) tree);
                return;
            }
        }
        informMatcherAbout(treeName, action);
    }
    // -----

    private void calculateStepDownPath(MatcherAction action, String treeName, UserAgentTreeWalkerParser.StepDownContext tree) {
        if (treeName.isEmpty()) {
            calculateInformPath(action,treeName + '.' + tree.name.getText(), tree.nextStep);
        } else {
            for (Integer number : NUMBER_RANGE_VISITOR.visit(tree.numberRange())) {
                calculateInformPath(action,treeName + '.' + "(" + number + ")" + tree.name.getText(), tree.nextStep);
            }
        }
    }

    private void calculateStepEqualsValuePath(MatcherAction action, String treeName, UserAgentTreeWalkerParser.StepEqualsValueContext tree) {
        informMatcherAbout(treeName + "=\"" + tree.value.getText() + "\"", action);
    }

    private void calculateStepWordRangePath(MatcherAction action, String treeName, UserAgentTreeWalkerParser.StepWordRangeContext tree) {
        WordRangeVisitor.Range range = WordRangeVisitor.getRange(tree.wordRange());
        lookingForRange(treeName, range);
        calculateInformPath(action,treeName + "[" + range.first + "-" + range.last + "]", tree.nextStep);
    }

    private static UserAgentTreeWalkerParser.MatcherContext getMatcher(UserAgentTreeWalkerParser.MatcherContext tree) {
        if (tree instanceof UserAgentTreeWalkerParser.MatcherCleanVersionContext)
            return ((UserAgentTreeWalkerParser.MatcherCleanVersionContext) tree).matcher();

        if (tree instanceof UserAgentTreeWalkerParser.MatcherNormalizeBrandContext)
            return ((UserAgentTreeWalkerParser.MatcherNormalizeBrandContext) tree).matcher();

        if (tree instanceof UserAgentTreeWalkerParser.MatcherPathLookupContext)
            return ((UserAgentTreeWalkerParser.MatcherPathLookupContext) tree).matcher();

        if (tree instanceof UserAgentTreeWalkerParser.MatcherWordRangeContext)
            return ((UserAgentTreeWalkerParser.MatcherWordRangeContext) tree).matcher();

        return null;
    }

    /**
     * @return The fixed value in case of a fixed value. NULL if a dynamic value
     */
    private String isFixedValue(ParserRuleContext pattern, Map<String, Map<String, String>> lookups) {
        return new UserAgentTreeWalkerBaseVisitor<String>() {
            @Override
            public String visitMatcherPathLookup(UserAgentTreeWalkerParser.MatcherPathLookupContext ctx) {
                String value = visit(ctx.matcher());
                if (value == null) {
                    return null;
                }
                // No we know this is a fixed value. Yet we can have a problem in the lookup that was
                // configured. If we have this then this is a FATAL error (it will fail always everywhere).

                Map<String, String> lookup = lookups.get(ctx.lookup.getText());
                if (lookup == null) {
                    throw new InvalidParserConfigurationException("Missing lookup \"" + ctx.lookup.getText() + "\" ");
                }

                String resultingValue = lookup.get(value.toLowerCase());
                if (resultingValue == null) {
                    if (ctx.defaultValue != null) {
                        return ctx.defaultValue.getText();
                    }
                    throw new InvalidParserConfigurationException(
                        "Fixed value >>" + value + "<< is missing in lookup: \"" + ctx.lookup.getText() + "\" ");
                }
                return resultingValue;
            }

            @Override
            public String visitPathFixedValue(UserAgentTreeWalkerParser.PathFixedValueContext ctx) {
                return ctx.value.getText();
            }
        }.visit(pattern);
    }

    private ParserRuleContext getRequiredPattern(String expression, Function<UserAgentTreeWalkerParser, ParserRuleContext> parseWalkerExpression) {
        InitErrorListener errorListener = new InitErrorListener(expression);

        CodePointCharStream input = CharStreams.fromString(expression);
        UserAgentTreeWalkerLexer lexer = new UserAgentTreeWalkerLexer(input);
        lexer.addErrorListener(errorListener);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        UserAgentTreeWalkerParser parser = new UserAgentTreeWalkerParser(tokens);

        parser.addErrorListener(errorListener);
        ParserRuleContext pattern = parseWalkerExpression.apply(parser); // parse

        // We couldn't ditch the double quotes around the fixed values in the parsing pase.
        // So we ditch them here. We simply walk the tree and modify some of the tokens.
        new UnQuoteValues().visit(pattern);
        return pattern;
    }


    private final class InitErrorListener implements ANTLRErrorListener {
        final String matchExpression;

        private InitErrorListener(String matchExpression) {
            this.matchExpression = matchExpression;
        }

        @Override
        public void syntaxError(
            Recognizer<?, ?> recognizer,
            Object offendingSymbol,
            int line,
            int charPositionInLine,
            String msg,
            RecognitionException e) {
            LOG.error("Syntax error");
            LOG.error("Source : {}", matchExpression);
            LOG.error("Message: {}", msg);
            throw new InvalidParserConfigurationException("Syntax error \"" + msg + "\" caused by \"" + matchExpression + "\".");
        }

        @Override
        public void reportAmbiguity(
            Parser recognizer,
            DFA dfa,
            int startIndex,
            int stopIndex,
            boolean exact,
            BitSet ambigAlts,
            ATNConfigSet configs) {
            // Ignore this type of problem
        }

        @Override
        public void reportAttemptingFullContext(
            Parser recognizer,
            DFA dfa,
            int startIndex,
            int stopIndex,
            BitSet conflictingAlts,
            ATNConfigSet configs) {
            // Ignore this type of problem
        }

        @Override
        public void reportContextSensitivity(
            Parser recognizer,
            DFA dfa,
            int startIndex,
            int stopIndex,
            int prediction,
            ATNConfigSet configs) {
            // Ignore this type of problem
        }
    }


    private static class UnQuoteValues extends UserAgentTreeWalkerBaseVisitor<Void> {
        private void unQuoteToken(Token token) {
            if (token instanceof CommonToken) {
                CommonToken commonToken = (CommonToken) token;
                commonToken.setStartIndex(commonToken.getStartIndex() + 1);
                commonToken.setStopIndex(commonToken.getStopIndex() - 1);
            }
        }

        @Override
        public Void visitMatcherPathLookup(UserAgentTreeWalkerParser.MatcherPathLookupContext ctx) {
            unQuoteToken(ctx.defaultValue);
            return super.visitMatcherPathLookup(ctx);
        }

        @Override
        public Void visitPathFixedValue(UserAgentTreeWalkerParser.PathFixedValueContext ctx) {
            unQuoteToken(ctx.value);
            return super.visitPathFixedValue(ctx);
        }

        @Override
        public Void visitStepEqualsValue(UserAgentTreeWalkerParser.StepEqualsValueContext ctx) {
            unQuoteToken(ctx.value);
            return super.visitStepEqualsValue(ctx);
        }

        @Override
        public Void visitStepNotEqualsValue(UserAgentTreeWalkerParser.StepNotEqualsValueContext ctx) {
            unQuoteToken(ctx.value);
            return super.visitStepNotEqualsValue(ctx);
        }

        @Override
        public Void visitStepStartsWithValue(UserAgentTreeWalkerParser.StepStartsWithValueContext ctx) {
            unQuoteToken(ctx.value);
            return super.visitStepStartsWithValue(ctx);
        }

        @Override
        public Void visitStepEndsWithValue(UserAgentTreeWalkerParser.StepEndsWithValueContext ctx) {
            unQuoteToken(ctx.value);
            return super.visitStepEndsWithValue(ctx);
        }

        @Override
        public Void visitStepContainsValue(UserAgentTreeWalkerParser.StepContainsValueContext ctx) {
            unQuoteToken(ctx.value);
            return super.visitStepContainsValue(ctx);
        }
    }

}
