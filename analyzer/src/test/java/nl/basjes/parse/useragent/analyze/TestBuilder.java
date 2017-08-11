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

import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;
import nl.basjes.parse.useragent.debug.UserAgentAnalyzerTester;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TestBuilder {

    @Test
    public void testLimitedFields() {
        UserAgentAnalyzer userAgentAnalyzer =
            UserAgentAnalyzerTester
                .newBuilder()
                .preheat(100)
                .hideMatcherLoadStats()
                .showMatcherLoadStats()
                .withAllFields()
                .withField("DeviceClass")
                .withField("AgentNameVersionMajor")
                .build();

        UserAgent parsedAgent = userAgentAnalyzer.parse("Mozilla/5.0 (Linux; Android 7.0; Nexus 6 Build/NBD90Z) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2785.124 Mobile Safari/537.36");

        // The requested fields
        Assert.assertEquals("Phone",                    parsedAgent.getValue("DeviceClass"              )); // Phone
        Assert.assertEquals("Chrome 53",                parsedAgent.getValue("AgentNameVersionMajor"    )); // Chrome 53

        // The fields that are internally needed to build the requested fields
        Assert.assertEquals("Chrome",                   parsedAgent.getValue("AgentName"                )); // Chrome
        Assert.assertEquals("53.0.2785.124",            parsedAgent.getValue("AgentVersion"             )); // 53.0.2785.124
        Assert.assertEquals("53",                       parsedAgent.getValue("AgentVersionMajor"        )); // 53
        Assert.assertEquals("Chrome 53.0.2785.124",     parsedAgent.getValue("AgentNameVersion"         )); // Chrome 53.0.2785.124

        // The rest must be at confidence -1 (i.e. no rules fired)
        Assert.assertEquals(-1, parsedAgent.get("DeviceName"                   ).confidence); // Nexus 6
        Assert.assertEquals(-1, parsedAgent.get("DeviceBrand"                  ).confidence); // Google
        Assert.assertEquals(-1, parsedAgent.get("OperatingSystemClass"         ).confidence); // Mobile
        Assert.assertEquals(-1, parsedAgent.get("OperatingSystemName"          ).confidence); // Android
        Assert.assertEquals(-1, parsedAgent.get("OperatingSystemVersion"       ).confidence); // 7.0
        Assert.assertEquals(-1, parsedAgent.get("OperatingSystemNameVersion"   ).confidence); // Android 7.0
//        Assert.assertEquals(-1, parsedAgent.get("OperatingSystemVersionBuild"  ).getConfidence()); // NBD90Z
        Assert.assertEquals(-1, parsedAgent.get("LayoutEngineClass"            ).confidence); // Browser
        Assert.assertEquals(-1, parsedAgent.get("LayoutEngineName"             ).confidence); // Blink
        Assert.assertEquals(-1, parsedAgent.get("LayoutEngineVersion"          ).confidence); // 53.0
        Assert.assertEquals(-1, parsedAgent.get("LayoutEngineVersionMajor"     ).confidence); // 53
        Assert.assertEquals(-1, parsedAgent.get("LayoutEngineNameVersion"      ).confidence); // Blink 53.0
        Assert.assertEquals(-1, parsedAgent.get("LayoutEngineNameVersionMajor" ).confidence); // Blink 53
        Assert.assertEquals(-1, parsedAgent.get("AgentClass"                   ).confidence); // Browser
    }

    @Rule
    public final ExpectedException expectedEx = ExpectedException.none();

    @Test
    public void testAskingForImpossibleField() {
        expectedEx.expect(InvalidParserConfigurationException.class);
        expectedEx.expectMessage("We cannot provide these fields:[FirstNonexistentField, SecondNonexistentField]");

        UserAgentAnalyzer
            .newBuilder()
            .hideMatcherLoadStats()
            .withField("FirstNonexistentField")
            .withField("DeviceClass")
            .withField("SecondNonexistentField")
            .build();
    }

}
