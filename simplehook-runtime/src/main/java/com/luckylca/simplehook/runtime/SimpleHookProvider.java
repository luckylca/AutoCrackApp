package com.luckylca.simplehook.runtime;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Base64;
import com.luckylca.simplehook.core.HookRule;
import com.luckylca.simplehook.core.RuleValidationException;
import com.luckylca.simplehook.core.SimpleHookLimits;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class SimpleHookProvider extends ContentProvider {
    private static final long HEARTBEAT_MAX_AGE_MS = 5_000L;
    private static final Map<String, JSONObject> HEARTBEATS = new ConcurrentHashMap<>();
    private RuleStore rules;
    private JsonLogStore logs;

    @Override
    public boolean onCreate() {
        rules = new RuleStore(contextOrThrow());
        logs = new JsonLogStore(contextOrThrow());
        return true;
    }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        try {
            JSONObject request = decodeRequest(argument, extras);
            authorize(method, request);
            JSONObject result = switch (method) {
                case "status" -> status();
                case "rules_list" -> ok().put("rules", rules.listWithState()).put("generation", rules.generation());
                case "rules_show" -> show(request.getString("id"));
                case "rules_add" -> upsert(request.getJSONObject("rule"), true);
                case "rules_update" -> upsert(request.getJSONObject("rule"), false);
                case "rules_remove" -> remove(request.getString("id"));
                case "rules_enable" -> enable(request.getString("id"), true);
                case "rules_disable" -> enable(request.getString("id"), false);
                case "rules_for_package" -> ok()
                        .put("rules", rules.rulesForPackage(request.getString("package"), request.optString("process")))
                        .put("generation", rules.generation());
                case "rule_state" -> updateState(request);
                case "reload" -> ok().put("generation", rules.reload()).put("requires_restart", false);
                case "append_log" -> appendLog(request.getJSONObject("entry"));
                case "logs" -> ok().put("logs", logs.query(
                        nullable(request, "rule_id"), nullable(request, "package"), request.optInt("limit", 500)));
                case "heartbeat" -> heartbeat(request);
                case "inspect_submit" -> inspectSubmit(request);
                case "inspect_pending" -> inspectPending(request.getString("package"));
                case "inspect_complete" -> inspectComplete(request);
                case "inspect_result" -> inspectResult(request.getString("request_id"));
                case "limits" -> limits();
                default -> error("UNKNOWN_METHOD", "Unknown provider method: " + method);
            };
            return bundle(result);
        } catch (RuleValidationException error) {
            return bundle(error(error.getCode(), error.getMessage()));
        } catch (SecurityException error) {
            return bundle(error("ACCESS_DENIED", error.getMessage()));
        } catch (Exception error) {
            return bundle(error("RUNTIME_ERROR", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    private void authorize(String method, JSONObject request) {
        int uid = Binder.getCallingUid();
        if (uid == 0 || uid == Process.SHELL_UID || uid == Process.myUid()) return;
        if (!Set.of("rules_for_package", "rule_state", "append_log", "heartbeat", "inspect_pending", "inspect_complete").contains(method)) {
            throw new SecurityException("This operation requires Android root or shell");
        }
        JSONObject entry = request.optJSONObject("entry");
        String claimedPackage = "append_log".equals(method)
                ? (entry == null ? null : entry.optString("package", null))
                : request.optString("package", null);
        String[] callerPackages = contextOrThrow().getPackageManager().getPackagesForUid(uid);
        if (claimedPackage == null || callerPackages == null || !Arrays.asList(callerPackages).contains(claimedPackage)) {
            throw new SecurityException("Runtime caller may only access its own package");
        }
        if ("rule_state".equals(method)) {
            try {
                JSONObject rule = rules.find(request.optString("id"));
                if (rule == null || !claimedPackage.equals(rule.optString("package"))) {
                    throw new SecurityException("Rule does not belong to the runtime caller");
                }
            } catch (JSONException error) {
                throw new SecurityException("Invalid rule state request");
            }
        }
    }

    private JSONObject status() throws JSONException {
        long oldestAllowed = System.currentTimeMillis() - HEARTBEAT_MAX_AGE_MS;
        HEARTBEATS.entrySet().removeIf(entry -> entry.getValue().optLong("last_seen") < oldestAllowed);
        JSONArray active = new JSONArray();
        HEARTBEATS.values().forEach(active::put);
        JSONArray all = rules.rules();
        int enabled = 0;
        for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).optBoolean("enabled", true)) enabled++;
        return ok().put("version", "0.1.0")
                .put("runtime", new JSONObject().put("available", true).put("module_enabled", !HEARTBEATS.isEmpty()))
                .put("rules", new JSONObject().put("total", all.length()).put("active", enabled))
                .put("processes", active);
    }

    private JSONObject show(String id) throws JSONException {
        JSONObject rule = rules.find(id);
        if (rule == null) return error("RULE_NOT_FOUND", "Rule not found: " + id);
        JSONObject result = ok().put("rule", rule);
        JSONObject state = rules.state(id);
        if (state != null) result.put("runtime", state);
        return result;
    }

    private JSONObject upsert(JSONObject rule, boolean createOnly) throws JSONException {
        boolean updated = rules.upsert(rule, createOnly);
        return ok().put("rule", rule).put("created", !updated).put("requires_restart", false);
    }

    private JSONObject remove(String id) throws JSONException {
        if (!rules.remove(id)) return error("RULE_NOT_FOUND", "Rule not found: " + id);
        return ok().put("removed", id).put("requires_restart", false);
    }

    private JSONObject enable(String id, boolean enabled) throws JSONException {
        if (!rules.setEnabled(id, enabled)) return error("RULE_NOT_FOUND", "Rule not found: " + id);
        return ok().put("id", id).put("enabled", enabled).put("requires_restart", false);
    }

    private JSONObject updateState(JSONObject request) throws JSONException {
        rules.setState(
                request.getString("id"),
                com.luckylca.simplehook.core.RuleState.valueOf(request.getString("state")),
                nullable(request, "detail"));
        return ok();
    }

    private JSONObject appendLog(JSONObject entry) throws Exception {
        logs.append(entry);
        return ok();
    }

    private JSONObject heartbeat(JSONObject request) throws JSONException {
        recordHeartbeat(request);
        return ok();
    }

    static void recordHeartbeat(JSONObject request) throws JSONException {
        JSONObject value = new JSONObject(request.toString()).put("last_seen", System.currentTimeMillis());
        HEARTBEATS.put(request.getString("package") + ":" + request.getInt("pid"), value);
    }

    private JSONObject inspectSubmit(JSONObject request) throws JSONException {
        String id = rules.submitInspection(request);
        return ok().put("request_id", id);
    }

    private JSONObject inspectPending(String packageName) throws JSONException {
        return ok().put("requests", rules.pendingInspections(packageName));
    }

    private JSONObject inspectComplete(JSONObject request) throws JSONException {
        String id = request.getString("request_id");
        rules.completeInspection(id, request.getJSONObject("result"));
        return ok();
    }

    private JSONObject inspectResult(String id) throws JSONException {
        JSONObject result = rules.consumeInspectionResult(id);
        return result == null ? ok().put("pending", true) : ok().put("pending", false).put("result", result);
    }

    private static JSONObject limits() throws JSONException {
        return ok().put("limits", new JSONObject()
                .put("max_rules", SimpleHookLimits.MAX_RULES)
                .put("max_hooked_methods", SimpleHookLimits.MAX_HOOKED_METHODS)
                .put("max_wildcard_expansion", SimpleHookLimits.MAX_WILDCARD_EXPANSION)
                .put("max_logs_per_second", SimpleHookLimits.MAX_LOGS_PER_SECOND)
                .put("max_log_entry_bytes", SimpleHookLimits.MAX_LOG_ENTRY_BYTES)
                .put("max_stack_trace_chars", SimpleHookLimits.MAX_STACK_TRACE_CHARS)
                .put("max_log_file_bytes", SimpleHookLimits.MAX_LOG_FILE_BYTES)
                .put("max_log_files", SimpleHookLimits.MAX_LOG_FILES));
    }

    private static JSONObject decodeRequest(String argument, Bundle extras) throws JSONException {
        if (extras != null && extras.containsKey("json")) return new JSONObject(extras.getString("json", "{}"));
        if (extras != null && extras.containsKey("base64")) {
            byte[] decoded = Base64.decode(extras.getString("base64"), Base64.DEFAULT);
            return new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        }
        if (argument != null && !argument.isBlank()) return new JSONObject(argument);
        return new JSONObject();
    }

    private static Bundle bundle(JSONObject json) {
        Bundle result = new Bundle();
        result.putString(SimpleHookContract.RESULT_JSON, json.toString());
        return result;
    }

    private static JSONObject ok() {
        try {
            return new JSONObject().put("ok", true);
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static JSONObject error(String code, String message) {
        try {
            return new JSONObject().put("ok", false).put("error", new JSONObject().put("code", code).put("message", message));
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String nullable(JSONObject json, String key) {
        return !json.has(key) || json.isNull(key) ? null : json.optString(key, null);
    }

    private android.content.Context contextOrThrow() {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider context unavailable");
        return context;
    }

    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
