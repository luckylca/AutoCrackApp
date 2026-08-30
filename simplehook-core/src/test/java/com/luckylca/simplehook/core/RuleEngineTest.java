package com.luckylca.simplehook.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class RuleEngineTest {
    @Test
    public void parsesExactOverloadAndReplacement() {
        HookRule rule = HookRule.parse(baseRule("replace_return", "int", "100"));
        assertEquals("overload", rule.target.method);
        assertEquals(1, rule.target.parameters.size());
        assertEquals("int", rule.target.parameters.get(0));
        assertEquals(100, ValueCodec.coerce(rule.action.value, "int"));
    }

    @Test
    public void supportsPrimitiveBoxedStringCharAndNull() {
        assertEquals(7, ValueCodec.coerce(7, "java.lang.Integer"));
        assertEquals(true, ValueCodec.coerce(true, "boolean"));
        assertEquals('x', ValueCodec.coerce("x", "char"));
        assertEquals("hello", ValueCodec.coerce("hello", "java.lang.String"));
        assertNull(ValueCodec.coerce(JSONObject.NULL, "java.lang.Integer"));
        assertThrows(RuleValidationException.class, () -> ValueCodec.coerce(JSONObject.NULL, "int"));
    }

    @Test
    public void evaluatesSafeConditions() {
        assertTrue(ConditionEvaluator.matches("eq", 10, 10));
        assertTrue(ConditionEvaluator.matches("gte", 10, 9));
        assertTrue(ConditionEvaluator.matches("contains", "simplehook", "hook"));
        assertTrue(ConditionEvaluator.matches("is_null", null, null));
        assertFalse(ConditionEvaluator.matches("starts_with", "hook", "simple"));
    }

    @Test
    public void rejectsGlobalWildcardInvalidIndexAndNullPrimitive() {
        assertCode("WILDCARD_TOO_BROAD", baseRule("record", "int", "null").replace("overload", "*"));
        assertCode("INVALID_ARGUMENT_INDEX", baseRule("replace_argument", "int", "7")
                .replace("\"argument_index\": 0", "\"argument_index\": 2"));
        assertCode("NULL_FOR_PRIMITIVE", baseRule("replace_return", "int", "null"));
    }

    @Test
    public void rejectsUnknownPropertiesAndWrongJsonTypes() {
        assertCode("INVALID_SCHEMA", baseRule("record", "int", "null")
                .replace("\"enabled\": true", "\"enabled\": \"true\""));
        assertCode("INVALID_SCHEMA", baseRule("record", "int", "null")
                .replace("\"id\": \"rule_001\"", "\"id\": \"rule_001\", \"eval\": \"code\""));
        assertCode("INVALID_TYPE", baseRule("record", "int", "null")
                .replace("\"parameters\": [\"int\"]", "\"parameters\": [7]"));
    }

    private static void assertCode(String expected, String rule) {
        RuleValidationException error = assertThrows(RuleValidationException.class, () -> HookRule.parse(rule));
        assertEquals(expected, error.getCode());
    }

    private static String baseRule(String action, String returnType, String value) {
        String argumentIndex = action.equals("replace_argument") ? ", \"argument_index\": 0" : "";
        return """
                {
                  "schema_version": 1,
                  "id": "rule_001",
                  "enabled": true,
                  "package": "com.example.test",
                  "process": null,
                  "target": {
                    "class": "com.example.test.TestClass",
                    "method": "overload",
                    "constructor": false,
                    "parameters": ["int"],
                    "return_type": "%s"
                  },
                  "action": {"type": "%s", "value": %s%s},
                  "logging": {"enabled": true, "arguments": true, "return_value": true}
                }
                """.formatted(returnType, action, value, argumentIndex);
    }
}
