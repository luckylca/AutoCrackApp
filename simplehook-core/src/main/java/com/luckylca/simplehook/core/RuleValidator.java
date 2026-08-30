package com.luckylca.simplehook.core;

import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RuleValidator {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern METHOD_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\*)?");
    private static final Set<String> ACTIONS = Set.of(
            "record", "replace_return", "replace_argument", "before", "after",
            "skip_original", "field_read", "field_write", "field_record");
    private static final Set<String> OPERATORS = Set.of(
            "eq", "ne", "gt", "gte", "lt", "lte", "contains", "starts_with",
            "ends_with", "is_null", "not_null");

    private RuleValidator() {}

    public static void validate(JSONObject rule) {
        rejectUnknown(rule, Set.of("schema_version", "id", "enabled", "package", "process",
                "target", "action", "logging", "condition"), "rule");
        require(rule.has("schema_version") && rule.opt("schema_version") instanceof Integer,
                "INVALID_SCHEMA", "schema_version must be an integer");
        if (rule.optInt("schema_version", -1) != HookRule.SCHEMA_VERSION) {
            fail("UNSUPPORTED_SCHEMA", "schema_version must be 1");
        }
        require(rule.opt("id") instanceof String, "INVALID_SCHEMA", "id must be a string");
        require(ID.matcher(rule.optString("id")).matches(), "INVALID_ID", "Invalid rule id");
        require(rule.has("enabled") && rule.opt("enabled") instanceof Boolean,
                "INVALID_SCHEMA", "enabled must be a boolean");
        require(rule.opt("package") instanceof String, "INVALID_SCHEMA", "package must be a string");
        require(CLASS_NAME.matcher(rule.optString("package")).matches(), "INVALID_PACKAGE", "Invalid Android package name");
        if (rule.has("process") && !rule.isNull("process")) {
            require(rule.opt("process") instanceof String && !rule.optString("process").isBlank(),
                    "INVALID_SCHEMA", "process must be null or a non-empty string");
        }

        JSONObject target = requireObject(rule, "target");
        rejectUnknown(target, Set.of("class", "method", "constructor", "parameters", "return_type", "field"), "target");
        require(target.opt("class") instanceof String, "INVALID_SCHEMA", "target.class must be a string");
        String className = target.optString("class");
        require(CLASS_NAME.matcher(className).matches(), "INVALID_CLASS", "Invalid target class name");
        if (target.has("constructor")) {
            require(target.opt("constructor") instanceof Boolean,
                    "INVALID_SCHEMA", "target.constructor must be a boolean");
        }
        if (target.has("method")) {
            require(target.opt("method") instanceof String, "INVALID_SCHEMA", "target.method must be a string");
        }
        boolean constructor = target.optBoolean("constructor", false);
        String method = target.optString("method", "");
        String field = nullableString(target, "field");

        JSONObject action = requireObject(rule, "action");
        rejectUnknown(action, Set.of("type", "value", "argument_index"), "action");
        require(action.opt("type") instanceof String, "INVALID_SCHEMA", "action.type must be a string");
        String actionType = action.optString("type");
        require(ACTIONS.contains(actionType), "INVALID_ACTION", "Unsupported action type: " + actionType);
        boolean fieldAction = actionType.startsWith("field_");
        if (fieldAction) {
            require(field != null && !field.isBlank(), "INVALID_FIELD", "Field actions require target.field");
            require(!constructor && method.isEmpty(), "INVALID_TARGET", "Field actions cannot target a method or constructor");
        } else if (constructor) {
            require(method.isEmpty() || "<init>".equals(method), "INVALID_TARGET", "Constructor method must be empty or <init>");
            require(Set.of("record", "before", "after").contains(actionType), "INVALID_ACTION", "Constructor supports record, before, or after");
        } else {
            require(!"*".equals(method), "WILDCARD_TOO_BROAD", "A global method wildcard is not allowed");
            require(METHOD_NAME.matcher(method).matches(), "INVALID_METHOD", "Invalid method name");
        }

        JSONArray parameters = target.optJSONArray("parameters");
        require(parameters != null, "INVALID_PARAMETERS", "target.parameters is required");
        require(parameters.length() <= 64, "INVALID_PARAMETERS", "target.parameters may contain at most 64 entries");
        for (int i = 0; i < parameters.length(); i++) {
            require(parameters.opt(i) instanceof String, "INVALID_TYPE", "Parameter type must be a string at index " + i);
            String type = parameters.optString(i, "");
            require(ValueCodec.isValidTypeName(type), "INVALID_TYPE", "Invalid parameter type at index " + i);
        }
        String returnType = nullableString(target, "return_type");
        if (target.has("return_type") && !target.isNull("return_type")) {
            require(target.opt("return_type") instanceof String,
                    "INVALID_SCHEMA", "target.return_type must be null or a string");
        }
        if (target.has("field") && !target.isNull("field")) {
            require(target.opt("field") instanceof String,
                    "INVALID_SCHEMA", "target.field must be null or a string");
        }
        if (!fieldAction && !constructor) {
            require(returnType != null && ValueCodec.isValidTypeName(returnType), "INVALID_RETURN_TYPE", "A valid return_type is required");
        }

        if ("replace_argument".equals(actionType)) {
            require(action.opt("argument_index") instanceof Integer,
                    "INVALID_ARGUMENT_INDEX", "argument_index must be an integer");
            int index = action.optInt("argument_index", -1);
            require(index >= 0 && index < parameters.length(), "INVALID_ARGUMENT_INDEX", "argument_index is out of range");
            require(action.has("value"), "MISSING_VALUE", "replace_argument requires value");
            ValueCodec.coerce(action.opt("value"), parameters.optString(index));
        }
        if (Set.of("replace_return", "skip_original").contains(actionType)) {
            require(action.has("value"), "MISSING_VALUE", actionType + " requires value (explicit null is allowed)");
            require(!"void".equals(returnType), "INVALID_RETURN_TYPE", actionType + " cannot target void");
            ValueCodec.coerce(action.opt("value"), returnType);
        }
        if ("field_write".equals(actionType)) {
            require(action.has("value"), "MISSING_VALUE", "field_write requires value");
        }

        JSONObject condition = rule.optJSONObject("condition");
        if (rule.has("condition") && !rule.isNull("condition")) {
            require(condition != null, "INVALID_SCHEMA", "condition must be null or an object");
        }
        if (condition != null) {
            rejectUnknown(condition, Set.of("source", "index", "operator", "value"), "condition");
            validateCondition(condition, parameters.length());
            String source = condition.optString("source");
            if (fieldAction) {
                require("field".equals(source), "INVALID_CONDITION", "Field actions require a field condition source");
            }
            if (Set.of("replace_argument", "skip_original", "before").contains(actionType)) {
                require(!"return_value".equals(source), "INVALID_CONDITION", actionType + " cannot use a return_value condition");
            }
        }

        JSONObject logging = rule.optJSONObject("logging");
        if (rule.has("logging") && !rule.isNull("logging")) {
            require(logging != null, "INVALID_SCHEMA", "logging must be an object");
        }
        if (logging != null) {
            rejectUnknown(logging, Set.of("enabled", "arguments", "return_value", "stack_trace"), "logging");
            for (String key : Set.of("enabled", "arguments", "return_value", "stack_trace")) {
                if (logging.has(key)) require(logging.opt(key) instanceof Boolean,
                        "INVALID_SCHEMA", "logging." + key + " must be a boolean");
            }
        }
    }

    private static void validateCondition(JSONObject condition, int parameterCount) {
        require(condition.opt("source") instanceof String,
                "INVALID_CONDITION", "Condition source must be a string");
        String source = condition.optString("source");
        require(Set.of("argument", "return_value", "field").contains(source), "INVALID_CONDITION", "Unsupported condition source");
        String operator = condition.optString("operator");
        require(condition.opt("operator") instanceof String,
                "INVALID_CONDITION", "Condition operator must be a string");
        require(OPERATORS.contains(operator), "INVALID_CONDITION", "Unsupported condition operator");
        if ("argument".equals(source)) {
            require(condition.opt("index") instanceof Integer,
                    "INVALID_CONDITION", "Condition argument index must be an integer");
            int index = condition.optInt("index", -1);
            require(index >= 0 && index < parameterCount, "INVALID_CONDITION", "Condition argument index is out of range");
        }
        if (!Set.of("is_null", "not_null").contains(operator)) {
            require(condition.has("value"), "INVALID_CONDITION", "Condition operator requires value");
        }
    }

    private static JSONObject requireObject(JSONObject parent, String key) {
        JSONObject value = parent.optJSONObject(key);
        if (value == null) fail("INVALID_SCHEMA", key + " must be an object");
        return value;
    }

    private static void rejectUnknown(JSONObject object, Set<String> allowed, String label) {
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            require(allowed.contains(key), "INVALID_SCHEMA", "Unknown " + label + " property: " + key);
        }
    }

    private static String nullableString(JSONObject object, String key) {
        return !object.has(key) || object.isNull(key) ? null : object.optString(key, null);
    }

    private static void require(boolean condition, String code, String message) {
        if (!condition) fail(code, message);
    }

    private static void fail(String code, String message) {
        throw new RuleValidationException(code, message);
    }
}
