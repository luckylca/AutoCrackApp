package com.luckylca.simplehook.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class HookRule {
    public static final int SCHEMA_VERSION = 1;

    public final int schemaVersion;
    public final String id;
    public final boolean enabled;
    public final String packageName;
    public final String process;
    public final Target target;
    public final Action action;
    public final Logging logging;
    public final Condition condition;

    private HookRule(
            int schemaVersion,
            String id,
            boolean enabled,
            String packageName,
            String process,
            Target target,
            Action action,
            Logging logging,
            Condition condition) {
        this.schemaVersion = schemaVersion;
        this.id = id;
        this.enabled = enabled;
        this.packageName = packageName;
        this.process = process;
        this.target = target;
        this.action = action;
        this.logging = logging;
        this.condition = condition;
    }

    public static HookRule parse(String text) {
        try {
            return parse(new JSONObject(text));
        } catch (JSONException error) {
            throw new RuleValidationException("INVALID_JSON", error.getMessage());
        }
    }

    public static HookRule parse(JSONObject json) {
        RuleValidator.validate(json);
        JSONObject targetJson = json.optJSONObject("target");
        JSONArray parameterJson = targetJson.optJSONArray("parameters");
        List<String> parameters = new ArrayList<>();
        if (parameterJson != null) {
            for (int i = 0; i < parameterJson.length(); i++) {
                parameters.add(parameterJson.optString(i));
            }
        }
        Target target = new Target(
                targetJson.optString("class"),
                targetJson.optString("method", ""),
                targetJson.optBoolean("constructor", false),
                parameters,
                nullableString(targetJson, "return_type"),
                nullableString(targetJson, "field"));

        JSONObject actionJson = json.optJSONObject("action");
        Action action = new Action(
                actionJson.optString("type"),
                actionJson.has("value") ? actionJson.opt("value") : null,
                actionJson.has("argument_index") ? actionJson.optInt("argument_index") : null);

        JSONObject loggingJson = json.optJSONObject("logging");
        Logging logging = loggingJson == null
                ? new Logging(true, true, true, true)
                : new Logging(
                        loggingJson.optBoolean("enabled", true),
                        loggingJson.optBoolean("arguments", true),
                        loggingJson.optBoolean("return_value", true),
                        loggingJson.optBoolean("stack_trace", false));

        JSONObject conditionJson = json.optJSONObject("condition");
        Condition condition = conditionJson == null ? null : new Condition(
                conditionJson.optString("source"),
                conditionJson.has("index") ? conditionJson.optInt("index") : null,
                conditionJson.optString("operator"),
                conditionJson.has("value") ? conditionJson.opt("value") : null);

        return new HookRule(
                json.optInt("schema_version"),
                json.optString("id"),
                json.optBoolean("enabled", true),
                json.optString("package"),
                nullableString(json, "process"),
                target,
                action,
                logging,
                condition);
    }

    private static String nullableString(JSONObject json, String key) {
        return !json.has(key) || json.isNull(key) ? null : json.optString(key, null);
    }

    public static final class Target {
        public final String className;
        public final String method;
        public final boolean constructor;
        public final List<String> parameters;
        public final String returnType;
        public final String field;

        Target(
                String className,
                String method,
                boolean constructor,
                List<String> parameters,
                String returnType,
                String field) {
            this.className = className;
            this.method = method;
            this.constructor = constructor;
            this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
            this.returnType = returnType;
            this.field = field;
        }
    }

    public static final class Action {
        public final String type;
        public final Object value;
        public final Integer argumentIndex;

        Action(String type, Object value, Integer argumentIndex) {
            this.type = type;
            this.value = value == JSONObject.NULL ? null : value;
            this.argumentIndex = argumentIndex;
        }
    }

    public static final class Logging {
        public final boolean enabled;
        public final boolean arguments;
        public final boolean returnValue;
        public final boolean stackTrace;

        Logging(boolean enabled, boolean arguments, boolean returnValue, boolean stackTrace) {
            this.enabled = enabled;
            this.arguments = arguments;
            this.returnValue = returnValue;
            this.stackTrace = stackTrace;
        }
    }

    public static final class Condition {
        public final String source;
        public final Integer index;
        public final String operator;
        public final Object value;

        Condition(String source, Integer index, String operator, Object value) {
            this.source = source;
            this.index = index;
            this.operator = operator;
            this.value = value == JSONObject.NULL ? null : value;
        }
    }
}
