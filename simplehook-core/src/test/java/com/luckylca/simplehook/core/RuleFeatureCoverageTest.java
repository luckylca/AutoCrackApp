package com.luckylca.simplehook.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuleFeatureCoverageTest {
    @Test public void record() { assertEquals("record", parse("record", "null").action.type); }
    @Test public void before() { assertEquals("before", parse("before", "null").action.type); }
    @Test public void after() { assertEquals("after", parse("after", "null").action.type); }

    @Test
    public void replaceReturnPrimitiveAndStringTypes() {
        assertEquals(100, ValueCodec.coerce(parse("replace_return", "100").action.value, "int"));
        HookRule bool = HookRule.parse(document("replace_return", "false", "")
                .replace("\"return_type\": \"int\"", "\"return_type\": \"boolean\""));
        HookRule string = HookRule.parse(document("replace_return", "\"changed\"", "")
                .replace("\"return_type\": \"int\"", "\"return_type\": \"java.lang.String\""));
        assertEquals(false, ValueCodec.coerce(bool.action.value, "boolean"));
        assertEquals("changed", ValueCodec.coerce(string.action.value, "java.lang.String"));
    }

    @Test
    public void replaceArgumentAndSkipOriginal() {
        HookRule argument = HookRule.parse(document("replace_argument", "7", "\"argument_index\": 0,"));
        assertEquals(7, ValueCodec.coerce(argument.action.value, argument.target.parameters.get(0)));
        assertEquals("skip_original", parse("skip_original", "9").action.type);
    }

    @Test
    public void exactOverloadSignaturesRemainDistinct() {
        HookRule integer = HookRule.parse(document("record", "null", ""));
        HookRule string = HookRule.parse(document("record", "null", "").replace("[\"int\"]", "[\"java.lang.String\"]"));
        assertFalse(integer.target.parameters.equals(string.target.parameters));
    }

    @Test
    public void constructorRule() {
        String constructor = document("record", "null", "")
                .replace("\"method\": \"overload\",", "\"method\": \"\",")
                .replace("\"constructor\": false", "\"constructor\": true")
                .replace("\"return_type\": \"int\"", "\"return_type\": null");
        assertTrue(HookRule.parse(constructor).target.constructor);
    }

    @Test
    public void staticAndInstanceFieldSchemas() {
        String field = document("field_write", "12", "")
                .replace("\"method\": \"overload\",", "\"method\": \"\", \"field\": \"instanceField\",")
                .replace("\"return_type\": \"int\"", "\"return_type\": null");
        HookRule parsed = HookRule.parse(field);
        assertEquals("instanceField", parsed.target.field);
        assertEquals("field_write", parsed.action.type);
    }

    @Test
    public void conditionsAndNullBoxedValues() {
        assertTrue(ConditionEvaluator.matches("ends_with", "SimpleHook", "Hook"));
        assertFalse(ConditionEvaluator.matches("lt", "not a number", 10));
        assertFalse(ConditionEvaluator.matches("lte", null, 10));
        assertNull(ValueCodec.coerce(null, "java.lang.Integer"));
    }

    @Test
    public void arraySignaturesResolveToJvmArrayClasses() throws Exception {
        assertEquals(int[].class, ValueCodec.resolveType("int[]", getClass().getClassLoader()));
        assertEquals(String[].class, ValueCodec.resolveType("java.lang.String[]", getClass().getClassLoader()));
    }

    @Test
    public void allRuntimeStatesAreDefined() {
        for (String state : new String[]{"CREATED", "ENABLED", "WAITING_FOR_PROCESS", "WAITING_FOR_CLASS",
                "INSTALLED", "ACTIVE", "FAILED", "DISABLED"}) {
            assertEquals(state, RuleState.valueOf(state).name());
        }
    }

    private static HookRule parse(String action, String value) {
        return HookRule.parse(document(action, value, ""));
    }

    private static String document(String action, String value, String actionExtra) {
        return """
                {
                  "schema_version": 1,
                  "id": "feature_rule",
                  "enabled": true,
                  "package": "com.example.test",
                  "process": null,
                  "target": {
                    "class": "com.example.test.Target",
                    "method": "overload",
                    "constructor": false,
                    "parameters": ["int"],
                    "return_type": "int"
                  },
                  "action": {"type": "%s", %s "value": %s},
                  "logging": {"enabled": true, "arguments": true, "return_value": true}
                }
                """.formatted(action, actionExtra, value);
    }
}
