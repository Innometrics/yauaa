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

import nl.basjes.parse.useragent.analyze.Matcher;
import nl.basjes.parse.useragent.analyze.FieldSetter;
import nl.basjes.parse.useragent.parser.UserAgentBaseListener;
import nl.basjes.parse.useragent.utils.Normalize;
import nl.basjes.parse.useragent.utils.VersionSplitter;
import org.antlr.v4.runtime.ANTLRErrorListener;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.dfa.DFA;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Serializable;
import java.util.*;
import java.util.function.Function;

public class UserAgent extends UserAgentBaseListener implements Serializable, FieldSetter, ANTLRErrorListener {

    private static final Logger LOG = LogManager.getLogger(UserAgent.class);
    public static final String DEVICE_CLASS = "DeviceClass";
    public static final String DEVICE_BRAND = "DeviceBrand";
    public static final String DEVICE_NAME = "DeviceName";
    public static final String DEVICE_VERSION = "DeviceVersion";
    public static final String OPERATING_SYSTEM_CLASS = "OperatingSystemClass";
    public static final String OPERATING_SYSTEM_NAME = "OperatingSystemName";
    public static final String OPERATING_SYSTEM_VERSION = "OperatingSystemVersion";
    public static final String LAYOUT_ENGINE_CLASS = "LayoutEngineClass";
    public static final String LAYOUT_ENGINE_NAME = "LayoutEngineName";
    public static final String LAYOUT_ENGINE_VERSION = "LayoutEngineVersion";
    public static final String LAYOUT_ENGINE_VERSION_MAJOR = "LayoutEngineVersionMajor";
    public static final String AGENT_CLASS = "AgentClass";
    public static final String AGENT_NAME = "AgentName";
    public static final String AGENT_VERSION = "AgentVersion";
    public static final String AGENT_VERSION_MAJOR = "AgentVersionMajor";

    public static final String SYNTAX_ERROR = "__SyntaxError__";
    public static final String USERAGENT = "Useragent";

    public static final String SET_ALL_FIELDS = "__Set_ALL_Fields__";
    public static final String NULL_VALUE = "<<<null>>>";
    public static final String UNKNOWN_VALUE = "Unknown";
    public static final String UNKNOWN_VERSION = "??";

    public static final String[] STANDARD_FIELDS = {
        DEVICE_CLASS,
        DEVICE_BRAND,
        DEVICE_NAME,
        OPERATING_SYSTEM_CLASS,
        OPERATING_SYSTEM_NAME,
        OPERATING_SYSTEM_VERSION,
        LAYOUT_ENGINE_CLASS,
        LAYOUT_ENGINE_NAME,
        LAYOUT_ENGINE_VERSION,
        LAYOUT_ENGINE_VERSION_MAJOR,
        AGENT_CLASS,
        AGENT_NAME,
        AGENT_VERSION,
        AGENT_VERSION_MAJOR
    };

    private static final HashMap<String, AgentField> defaultValues = defaultValues();

    private boolean hasSyntaxError;
    private boolean hasAmbiguity;
    private int     ambiguityCount;

    public boolean hasSyntaxError() {
        return hasSyntaxError;
    }

    public boolean hasAmbiguity() {
        return hasAmbiguity;
    }

    public int getAmbiguityCount() {
        return ambiguityCount;
    }

    @Override
    public void syntaxError(
            Recognizer<?, ?> recognizer,
            Object offendingSymbol,
            int line,
            int charPositionInLine,
            String msg,
            RecognitionException e) {
        if (LOG.isDebugEnabled()) {
            LOG.error("Syntax error");
            LOG.error("Source : {}", userAgentString);
            LOG.error("Message: {}", msg);
        }
        hasSyntaxError = true;
        allFields.put(SYNTAX_ERROR, new AgentField(SYNTAX_ERROR, "true", 1));
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
        hasAmbiguity = true;
        ambiguityCount++;
//        allFields.put("__Ambiguity__",new AgentField("true"));
    }

    @Override
    public void reportAttemptingFullContext(
            Parser recognizer,
            DFA dfa,
            int startIndex,
            int stopIndex,
            BitSet conflictingAlts,
            ATNConfigSet configs) {
    }

    @Override
    public void reportContextSensitivity(
            Parser recognizer,
            DFA dfa,
            int startIndex,
            int stopIndex,
            int prediction,
            ATNConfigSet configs) {

    }

    // The original input value
    private final String userAgentString;

    public final Map<String, AgentField> allFields = new HashMap<>(defaultValues);

    public UserAgent(String userAgentString) {
        this.userAgentString = userAgentString;
    }


    private static HashMap<String, AgentField> defaultValues() {
        HashMap<String, AgentField> map = new HashMap<>(32);
        // Device : Family - Brand - Model
        map.put(DEVICE_CLASS,                  new AgentField(DEVICE_CLASS, UNKNOWN_VALUE, -1)); // Hacker / Cloud / Server / Desktop / Tablet / Phone / Watch
        map.put(DEVICE_BRAND,                  new AgentField(DEVICE_BRAND, UNKNOWN_VALUE, -1)); // (Google/AWS/Asure) / ????
        map.put(DEVICE_NAME,                   new AgentField(DEVICE_NAME, UNKNOWN_VALUE, -1)); // (Google/AWS/Asure) / ????

        // Operating system
        map.put(OPERATING_SYSTEM_CLASS,        new AgentField(OPERATING_SYSTEM_CLASS, UNKNOWN_VALUE, -1)); // Cloud, Desktop, Mobile, Embedded
        map.put(OPERATING_SYSTEM_NAME,         new AgentField(OPERATING_SYSTEM_NAME, UNKNOWN_VALUE, -1)); // ( Linux / Android / Windows ...)
        map.put(OPERATING_SYSTEM_VERSION,      new AgentField(OPERATING_SYSTEM_VERSION, UNKNOWN_VERSION, -1)); // 1.2 / 43 / ...

        // Engine : Class (=None/Hacker/Robot/Browser) - Name - Version
        map.put(LAYOUT_ENGINE_CLASS,           new AgentField(LAYOUT_ENGINE_CLASS, UNKNOWN_VALUE, -1)); // None / Hacker / Robot / Browser /
        map.put(LAYOUT_ENGINE_NAME,            new AgentField(LAYOUT_ENGINE_NAME, UNKNOWN_VALUE, -1)); // ( GoogleBot / Bing / ...) / (Trident / Gecko / ...)
        map.put(LAYOUT_ENGINE_VERSION,         new AgentField(LAYOUT_ENGINE_VERSION, UNKNOWN_VERSION, -1)); // 1.2 / 43 / ...
        map.put(LAYOUT_ENGINE_VERSION_MAJOR,   new AgentField(LAYOUT_ENGINE_VERSION_MAJOR, UNKNOWN_VERSION, -1)); // 1 / 43 / ...

        // Agent: Class (=Hacker/Robot/Browser) - Name - Version
        map.put(AGENT_CLASS,                   new AgentField(AGENT_CLASS, UNKNOWN_VALUE, -1)); // Hacker / Robot / Browser /
        map.put(AGENT_NAME,                    new AgentField(AGENT_NAME, UNKNOWN_VALUE, -1)); // ( GoogleBot / Bing / ...) / ( Firefox / Chrome / ... )
        map.put(AGENT_VERSION,                 new AgentField(AGENT_VERSION, UNKNOWN_VERSION, -1)); // 1.2 / 43 / ...
        map.put(AGENT_VERSION_MAJOR,           new AgentField(AGENT_VERSION_MAJOR, UNKNOWN_VERSION, -1)); // 1 / 43 / ...
        return map;
    }

    public String getUserAgentString() {
        return userAgentString;
    }

    static boolean isSystemField(String fieldname) {
        return  SET_ALL_FIELDS.equals(fieldname) ||
                SYNTAX_ERROR.equals(fieldname) ||
                USERAGENT.equals(fieldname);
    }

    public void processSetAll() {
        AgentField setAllField = allFields.get(SET_ALL_FIELDS);
        if (setAllField == null) return;
        boolean useDefault = NULL_VALUE.equals(setAllField.value);
        final Function<Map.Entry<String, AgentField>, AgentField> updateField = useDefault ?
            e-> new AgentField(e.getKey(), getDefaultValue(e.getKey()), -1) :
            e-> new AgentField(e.getKey(), setAllField.value, setAllField.confidence);

        for (Map.Entry<String, AgentField> fieldEntry : allFields.entrySet()) {
            if (isSystemField(fieldEntry.getKey())) continue;
            if (fieldEntry.getValue().confidence >= setAllField.confidence) continue;
            fieldEntry.setValue(updateField.apply(fieldEntry));
        }
    }

    public FieldSetter withMatcher(Matcher matcher) {
        return this;
    }

    @Override
    public void set(Collection<AgentField> values) {
        values.forEach(this::set);
    }

    private void set(AgentField incoming) {
        AgentField field = get(incoming.attribute);
        boolean wasEmpty = incoming.confidence == -1;
        boolean update = field == null || incoming.confidence > field.confidence;
        boolean useDefault = NULL_VALUE.equals(incoming.value) && !SET_ALL_FIELDS.equals(incoming.attribute);
        if (update) {
            allFields.put(incoming.attribute,
                useDefault
                    ? new AgentField(incoming.attribute, getDefaultValue(incoming.attribute), incoming.confidence)
                    : incoming);
        }

        if (!wasEmpty) {
            LOG.debug("{}  {} ({}) = {}", update ? "USE" : "SKIP", incoming.attribute, incoming.confidence, incoming.value);
        }
    }

    public void set(String attribute, String value, long confidence) {
        set(new AgentField(attribute, value, confidence));
    }

    public AgentField get(String attribute) {
        return allFields.get(attribute);
    }

    public String getValue(String fieldName) {
        AgentField field = get(fieldName);
        return field == null ? UNKNOWN_VALUE : field.value;
    }

    public Long getConfidence(String fieldName) {
        AgentField field = get(fieldName);
        return field == null || field.value == null ? -1L : field.confidence;
    }

    private String getDefaultValue(String name) {
        AgentField v = defaultValues.get(name);
        return v == null ? null : v.value;
    }

    public String toYamlTestCase() {
        return toYamlTestCase(false);
    }

    public String toYamlTestCase(boolean showConfidence) {
        StringBuilder sb = new StringBuilder(10240);
        sb.append("\n");
        sb.append("- test:\n");
//        sb.append("#    options:\n");
//        sb.append("#    - 'verbose'\n");
//        sb.append("#    - 'init'\n");
//        sb.append("#    - 'only'\n");
        sb.append("    input:\n");
//        sb.append("#      name: 'You can give the test case a name'\n");
        sb.append("      user_agent_string: '").append(userAgentString).append("'\n");
        sb.append("    expected:\n");

        List<String> fieldNames = getAvailableFieldNamesSorted();

        int maxNameLength = 30;
        int maxValueLength = 0;
        for (String fieldName : fieldNames) {
            maxNameLength = Math.max(maxNameLength, fieldName.length());
            String v = getValue(fieldName);
            if(v != null) maxValueLength = Math.max(maxValueLength, v.length());
        }

        for (String fieldName : fieldNames) {
            sb.append("      ").append(fieldName);
            for (int l = fieldName.length(); l < maxNameLength + 7; l++) {
                sb.append(' ');
            }
            AgentField field = get(fieldName);
            sb.append(": '").append(field.value).append('\'');
            if (showConfidence) {
                for (int l = field.value == null ? 0 : field.value.length(); l < maxValueLength + 5; l++) {
                    sb.append(' ');
                }
                sb.append("# ").append(field.confidence);
            }
            sb.append('\n');
        }
        sb.append("\n");
        sb.append("\n");

        return sb.toString();
    }


//    {
//        "agent": {
//            "user_agent_string": "Mozilla/5.0 (iPhone; CPU iPhone OS 9_2_1 like Mac OS X) AppleWebKit/601.1.46
//                                  (KHTML, like Gecko) Version/9.0 Mobile/13D15 Safari/601.1"
//            "AgentClass": "Browser",
//            "AgentName": "Safari",
//            "AgentVersion": "9.0",
//            "DeviceBrand": "Apple",
//            "DeviceClass": "Phone",
//            "DeviceFirmwareVersion": "13D15",
//            "DeviceName": "iPhone",
//            "LayoutEngineClass": "Browser",
//            "LayoutEngineName": "AppleWebKit",
//            "LayoutEngineVersion": "601.1.46",
//            "OperatingSystemClass": "Mobile",
//            "OperatingSystemName": "iOS",
//            "OperatingSystemVersion": "9_2_1",
//        }
//    }

    public String toJson() {
        List<String> fields = getAvailableFieldNames();
        fields.add("Useragent");
        return toJson(fields);
    }

    public String toJson(List<String> fieldNames) {
        StringBuilder sb = new StringBuilder(10240);
        sb.append("{");

        boolean addSeparator = false;
        for (String fieldName : fieldNames) {
            if (addSeparator) {
                sb.append(',');
            } else {
                addSeparator = true;
            }
            if ("Useragent".equals(fieldName)) {
                sb
                    .append("\"Useragent\"")
                    .append(':')
                    .append('"').append(StringEscapeUtils.escapeJson(getUserAgentString())).append('"');
            } else {
                sb
                    .append('"').append(StringEscapeUtils.escapeJson(fieldName)).append('"')
                    .append(':')
                    .append('"').append(StringEscapeUtils.escapeJson(getValue(fieldName))).append('"');
            }
        }

        sb.append("}");
        return sb.toString();
    }


    @Override
    public String toString() {
        return toString(getAvailableFieldNamesSorted());
    }
    public String toString(List<String> fieldNames) {
        StringBuilder sb = new StringBuilder("  - user_agent_string: '\"" + userAgentString + "\"'\n");
        int maxLength = 0;
        for (String fieldName : fieldNames) {
            maxLength = Math.max(maxLength, fieldName.length());
        }
        for (String fieldName : fieldNames) {
            if (!"Useragent".equals(fieldName)) {
                AgentField field = allFields.get(fieldName);
                if (field.value != null) {
                    sb.append("    ").append(fieldName);
                    for (int l = fieldName.length(); l < maxLength + 2; l++) {
                        sb.append(' ');
                    }
                    sb.append(": '").append(field.value).append('\'');
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    public List<String> getAvailableFieldNames() {
        List<String> resultSet = new ArrayList<>(allFields.size()+10);
        resultSet.addAll(Arrays.asList(STANDARD_FIELDS));
        for (String fieldName : allFields.keySet()) {
            if (resultSet.contains(fieldName)) continue;
            AgentField field = allFields.get(fieldName);
            if (field == null || field.value == null || field.confidence < 0) continue;
            resultSet.add(fieldName);
        }

        // This is not a field; this is a special operator.
        resultSet.remove(SET_ALL_FIELDS);
        return resultSet;
    }

    // We manually sort the list of fields to ensure the output is consistent.
    // Any unspecified fieldnames will be appended to the end.
    public static final String[] PRE_SORTED_FIELDS_LIST = {
        "DeviceClass",
        "DeviceName",
        "DeviceBrand",
        "DeviceCpu",
        "DeviceFirmwareVersion",
        "DeviceVersion",

        "OperatingSystemClass",
        "OperatingSystemName",
        "OperatingSystemVersion",
        "OperatingSystemNameVersion",
        "OperatingSystemVersionBuild",

        "LayoutEngineClass",
        "LayoutEngineName",
        "LayoutEngineVersion",
        "LayoutEngineVersionMajor",
        "LayoutEngineNameVersion",
        "LayoutEngineNameVersionMajor",
        "LayoutEngineBuild",

        "AgentClass",
        "AgentName",
        "AgentVersion",
        "AgentVersionMajor",
        "AgentNameVersion",
        "AgentNameVersionMajor",
        "AgentBuild",
        "AgentLanguage",
        "AgentLanguageCode",
        "AgentInformationEmail",
        "AgentInformationUrl",
        "AgentSecurity",
        "AgentUuid",

        "FacebookCarrier",
        "FacebookDeviceClass",
        "FacebookDeviceName",
        "FacebookDeviceVersion",
        "FacebookFBOP",
        "FacebookFBSS",
        "FacebookOperatingSystemName",
        "FacebookOperatingSystemVersion",

        "Anonymized",

        "HackerAttackVector",
        "HackerToolkit",

        "KoboAffiliate",
        "KoboPlatformId",

        "IECompatibilityVersion",
        "IECompatibilityVersionMajor",
        "IECompatibilityNameVersion",
        "IECompatibilityNameVersionMajor",

        SYNTAX_ERROR
    };

    public List<String> getAvailableFieldNamesSorted() {
        List<String> fieldNames = new ArrayList<>(getAvailableFieldNames());

        List<String> result = new ArrayList<>();
        for (String fieldName : PRE_SORTED_FIELDS_LIST) {
            if (fieldNames.remove(fieldName)) {
                result.add(fieldName);
            }
        }

        Collections.sort(fieldNames);
        result.addAll(fieldNames);
        return result;
    }

    void hardCodedPostProcessing(boolean addUserAgentStr, boolean canDetectHacker) {
        // If it is really really bad ... then it is a Hacker.
        if (canDetectHacker && "true".equals(getValue(SYNTAX_ERROR))) {
            if (getConfidence(DEVICE_CLASS) == -1 &&
                getConfidence(OPERATING_SYSTEM_CLASS) == -1 &&
                getConfidence(LAYOUT_ENGINE_CLASS) == -1)  {

                set(DEVICE_BRAND, "Hacker", 10);
                set(DEVICE_CLASS, "Hacker", 10);
                set(DEVICE_NAME, "Hacker", 10);
                set(DEVICE_VERSION, "Hacker", 10);
                set(OPERATING_SYSTEM_CLASS, "Hacker", 10);
                set(OPERATING_SYSTEM_NAME, "Hacker", 10);
                set(OPERATING_SYSTEM_VERSION, "Hacker", 10);
                set(LAYOUT_ENGINE_CLASS, "Hacker", 10);
                set(LAYOUT_ENGINE_NAME, "Hacker", 10);
                set(LAYOUT_ENGINE_VERSION, "Hacker", 10);
                set(LAYOUT_ENGINE_VERSION_MAJOR, "Hacker", 10);
                set(AGENT_CLASS, "Hacker", 10);
                set(AGENT_NAME, "Hacker", 10);
                set(AGENT_VERSION, "Hacker", 10);
                set(AGENT_VERSION_MAJOR, "Hacker", 10);
                set("HackerToolkit", "Unknown", 10);
                set("HackerAttackVector", "Unknown", 10);
            }
        }

        if(addUserAgentStr) set(USERAGENT, getUserAgentString(), 0);

        // !!!!!!!!!! NOTE !!!!!!!!!!!!
        // IF YOU ADD ANY EXTRA FIELDS YOU MUST ADD THEM TO THE BUILDER TOO !!!!
        // TODO: Perhaps this should be more generic. Like a "Post processor"  (Generic: Create fields from fields)?
        addMajorVersionField(AGENT_VERSION, AGENT_VERSION_MAJOR);
        addMajorVersionField(LAYOUT_ENGINE_VERSION, LAYOUT_ENGINE_VERSION_MAJOR);
        addMajorVersionField("WebviewAppVersion", "WebviewAppVersionMajor");

        concatFieldValuesNONDuplicated("AgentNameVersion",               AGENT_NAME,             AGENT_VERSION);
        concatFieldValuesNONDuplicated("AgentNameVersionMajor",          AGENT_NAME,             AGENT_VERSION_MAJOR);
        concatFieldValuesNONDuplicated("WebviewAppNameVersionMajor",     "WebviewAppName",       "WebviewAppVersionMajor");
        concatFieldValuesNONDuplicated("LayoutEngineNameVersion",        LAYOUT_ENGINE_NAME,     LAYOUT_ENGINE_VERSION);
        concatFieldValuesNONDuplicated("LayoutEngineNameVersionMajor",   LAYOUT_ENGINE_NAME,     LAYOUT_ENGINE_VERSION_MAJOR);
        concatFieldValuesNONDuplicated("OperatingSystemNameVersion",     OPERATING_SYSTEM_NAME,  OPERATING_SYSTEM_VERSION);

        // The device brand field is a mess.
        AgentField deviceBrand = get(DEVICE_BRAND);
        if (deviceBrand.nonNullConfidence() >= 0) {
            set(
                DEVICE_BRAND,
                Normalize.brand(deviceBrand.value),
                deviceBrand.confidence + 1);
        }

        // The email address is a mess
        AgentField email = get("AgentInformationEmail");
        if (email != null && email.nonNullConfidence() >= 0) {
            set(
                "AgentInformationEmail",
                Normalize.email(email.value),
                email.confidence + 1);
        }

        // Make sure the DeviceName always starts with the DeviceBrand
        AgentField deviceName = get(DEVICE_NAME);
        if (deviceName.nonNullConfidence() >= 0) {
            deviceBrand = get(DEVICE_BRAND);
            String deviceNameValue = (deviceName.value == null ? defaultValues.get(DEVICE_NAME) : deviceName).value;
            // In some cases it does start with the brand but without a separator following the brand
            deviceNameValue = (deviceBrand.nonNullConfidence() >= 0 && !deviceBrand.value.equals("Unknown"))
                ? Normalize.cleanupDeviceBrandName(deviceBrand.value, deviceNameValue)
                : Normalize.brand(deviceNameValue);

            set(
                DEVICE_NAME,
                deviceNameValue,
                deviceName.confidence + 1);
        }
    }

    private void concatFieldValuesNONDuplicated(String targetName, String firstName, String secondName) {
        AgentField first = get(firstName);
        AgentField second = get(secondName);
        boolean firstIsNull = first == null || first.value == null;
        boolean secondIsNull = second == null || second.value == null;
        if (firstIsNull) {
            if (!secondIsNull && second.confidence >= 0) {
                set(targetName, second.value, second.confidence);
            } //else both null - nothing to do
            return;
        }
        if(secondIsNull) {
            if (first.confidence >= 0) {
                set(targetName, first.value, first.confidence);
            }
            return;
        }
        if(first.value.equals(second.value)) {
            set(targetName, first.value, first.confidence);
            return;
        }
        if (second.value.startsWith(first.value)) {
            set(targetName, second.value, second.confidence);
            return;
        }
        set(targetName, first.value + " " + second.value, Math.max(first.confidence, second.confidence));
    }

    private void addMajorVersionField(String versionName, String majorVersionName) {
        AgentField agentVersionMajor = get(majorVersionName);
        if (agentVersionMajor != null && agentVersionMajor.value != null && agentVersionMajor.confidence != -1) return;
        AgentField agentVersion = get(versionName);
        if (agentVersion == null) return;
        set(
            majorVersionName,
            VersionSplitter.getInstance().getSingleSplit(agentVersion.value == null ? getDefaultValue(versionName) : agentVersion.value, 1),
            agentVersion.value == null ? -1 : agentVersion.confidence
        );
    }
}
