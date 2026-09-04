package com.luckylca.simplehook.runtime;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import android.util.Base64;
import com.luckylca.autocrack.runtime.shared.RuntimeRequestStore;
import com.luckylca.autocrack.runtime.shared.RuntimeDispatcher;
import com.luckylca.simplehook.core.HookRule;
import com.luckylca.simplehook.core.RuleValidationException;
import com.luckylca.simplehook.core.SimpleHookLimits;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class SimpleHookProvider extends ContentProvider {
    private static final long HEARTBEAT_MAX_AGE_MS = 5_000L;
    private static final Map<String, JSONObject> HEARTBEATS = new ConcurrentHashMap<>();
    private static final long[] RUNTIME_REQUEST_RETRY_DELAYS_MS = {0L, 250L, 1_000L, 3_000L, 7_000L};
    private static final String RUNTIME_REQUEST_BINDER = "runtime_request_binder";
    private static final String RUNTIME_REQUEST_BINDER_PROCESS = "runtime_request_binder_process";
    private static final String RUNTIME_REQUEST_BINDER_DESCRIPTOR = "com.luckylca.autocrack.runtime.REQUEST_ENDPOINT";
    private static final int RUNTIME_REQUEST_BINDER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    private static final Map<String, IBinder> RUNTIME_TARGET_BINDERS = new ConcurrentHashMap<>();
    private final Set<String> runtimeDeliveryAcknowledged = ConcurrentHashMap.newKeySet();
    private Handler runtimeDeliveryHandler;
    private RuleStore rules;
    private JsonLogStore logs;
    private RuntimeRequestStore runtimeRequests;

    @Override
    public boolean onCreate() {
        rules = new RuleStore(contextOrThrow());
        logs = new JsonLogStore(contextOrThrow());
        runtimeRequests = new RuntimeRequestStore(contextOrThrow());
        runtimeDeliveryHandler = new Handler(Looper.getMainLooper());
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
                case "reload" -> reloadRules();
                case "append_log" -> appendLog(request.getJSONObject("entry"));
                case "logs" -> ok().put("logs", logs.query(
                        nullable(request, "rule_id"), nullable(request, "package"), request.optInt("limit", 500)));
                case "heartbeat" -> heartbeat(request);
                case "inspect_submit" -> inspectSubmit(request);
                case "inspect_pending" -> inspectPending(request.getString("package"));
                case "inspect_complete" -> inspectComplete(request);
                case "inspect_result" -> inspectResult(request.getString("request_id"));
                case "limits" -> limits();
                case "runtime_submit" -> runtimeSubmit(request);
                case "runtime_pending" -> runtimeRequests.pending(request.getString("package"), nullable(request, "process"));
                case "runtime_result" -> runtimeRequests.result(request.getString("request_id"));
                case "runtime_status" -> runtimeRequests.status();
                case "runtime_clear" -> runtimeRequests.clear(request);
                case "runtime_execute_self" -> RuntimeDispatcher.execute(contextOrThrow(), request);
                case "runtime_complete" -> runtimeRequests.complete(request);
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
        if (!Set.of("rules_for_package", "rule_state", "append_log", "heartbeat", "inspect_pending", "inspect_complete", "runtime_pending", "runtime_complete").contains(method)) {
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
        boolean heartbeatRecent = !HEARTBEATS.isEmpty();
        return ok().put("version", "0.1.1")
                .put("runtime", new JSONObject()
                        .put("available", true)
                        .put("module_enabled", JSONObject.NULL)
                        .put("module_enabled_source", "unavailable_to_app_uid")
                        .put("runtime_attached", heartbeatRecent)
                        .put("heartbeat_recent", heartbeatRecent)
                        .put("heartbeat_max_age_ms", HEARTBEAT_MAX_AGE_MS)
                        .put("active_process_count", HEARTBEATS.size()))
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
        broadcastRulesForPackage(rule.optString("package", null));
        return ok().put("rule", rule).put("created", !updated).put("requires_restart", false);
    }

    private JSONObject remove(String id) throws JSONException {
        JSONObject existing = rules.find(id);
        if (existing == null || !rules.remove(id)) return error("RULE_NOT_FOUND", "Rule not found: " + id);
        broadcastRulesForPackage(existing.optString("package", null));
        return ok().put("removed", id).put("requires_restart", false);
    }

    private JSONObject enable(String id, boolean enabled) throws JSONException {
        JSONObject existing = rules.find(id);
        if (existing == null || !rules.setEnabled(id, enabled)) return error("RULE_NOT_FOUND", "Rule not found: " + id);
        broadcastRulesForPackage(existing.optString("package", null));
        return ok().put("id", id).put("enabled", enabled).put("requires_restart", false);
    }

    private JSONObject reloadRules() throws JSONException {
        long next = rules.reload();
        broadcastAllRulePackages();
        return ok().put("generation", next).put("requires_restart", false);
    }

    private JSONObject updateState(JSONObject request) throws JSONException {
        rules.setRuntimeState(
                request.getString("id"),
                com.luckylca.simplehook.core.RuleState.valueOf(request.getString("state")),
                nullable(request, "detail"),
                request.optLong("event_order", 0L),
                request.optLong("generation", 0L));
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

    private void broadcastAllRulePackages() throws JSONException {
        HashSet<String> packages = new HashSet<>();
        JSONArray all = rules.rules();
        for (int i = 0; i < all.length(); i++) {
            String packageName = all.getJSONObject(i).optString("package", null);
            if (packageName != null && !packageName.isBlank()) packages.add(packageName);
        }
        for (String packageName : packages) broadcastRulesForPackage(packageName);
    }

    private void broadcastRulesForPackage(String packageName) throws JSONException {
        if (packageName == null || packageName.isBlank()) return;
        JSONObject payload = ok()
                .put("package", packageName)
                .put("generation", rules.generation())
                .put(RuntimeChannel.CHANNEL_TOKEN, rules.channelToken())
                .put("rules", rulesForBroadcast(packageName));
        try {
            Intent intent = new Intent(RuntimeChannel.ACTION_RULES)
                    .setPackage(packageName)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(RuntimeChannel.JSON, payload.toString());
            if (Build.VERSION.SDK_INT >= 34) {
                BroadcastOptions options = BroadcastOptions.makeBasic()
                        .setShareIdentityEnabled(true)
                        .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE);
                contextOrThrow().sendOrderedBroadcast(intent, null, options.toBundle(), null,
                        null, Activity.RESULT_CANCELED, null, null);
            } else {
                contextOrThrow().sendBroadcast(intent);
            }
        } catch (Throwable error) {
            Log.w("SimpleHook", "SimpleHook rules broadcast failed", error);
        }
    }

    private JSONArray rulesForBroadcast(String packageName) throws JSONException {
        JSONArray selected = new JSONArray();
        JSONArray all = rules.rules();
        for (int i = 0; i < all.length(); i++) {
            JSONObject rule = all.getJSONObject(i);
            if (packageName.equals(rule.optString("package")) && rule.optBoolean("enabled", true)) {
                selected.put(new JSONObject(rule.toString()));
            }
        }
        return selected;
    }

    private JSONObject runtimeSubmit(JSONObject request) throws Exception {
        JSONObject result = runtimeRequests.submit(request);
        JSONObject stored = result.optJSONObject("request");
        if (result.optBoolean("ok") && stored != null && !deliverRuntimeRequestViaBinder(stored)) {
            scheduleRuntimeRequest(stored);
        }
        result.remove("request");
        return result;
    }

    private static String runtimeTargetKey(JSONObject request) {
        String packageName = request.optString("package", "");
        String processName = request.isNull("process") ? "" : request.optString("process", "");
        return packageName + "\n" + processName;
    }

    private boolean deliverRuntimeRequestViaBinder(JSONObject stored) {
        String key = runtimeTargetKey(stored);
        IBinder endpoint = RUNTIME_TARGET_BINDERS.get(key);
        if (endpoint == null) return false;
        if (!endpoint.isBinderAlive()) {
            RUNTIME_TARGET_BINDERS.remove(key, endpoint);
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(RUNTIME_REQUEST_BINDER_DESCRIPTOR);
            data.writeString(stored.toString());
            if (!endpoint.transact(RUNTIME_REQUEST_BINDER_TRANSACTION, data, reply, 0)) {
                RUNTIME_TARGET_BINDERS.remove(key, endpoint);
                return false;
            }
            reply.readException();
            boolean accepted = reply.readInt() == 1;
            String message = reply.readString();
            if (!accepted) {
                RUNTIME_TARGET_BINDERS.remove(key, endpoint);
                Log.w("SimpleHook", "Runtime request Binder declined: id="
                        + stored.optString("request_id", "") + " reason=" + message);
                return false;
            }
            runtimeDeliveryAcknowledged.add(stored.optString("request_id", ""));
            Log.i("SimpleHook", "Runtime request delivered via Binder: id="
                    + stored.optString("request_id", "") + " target=" + key.replace('\n', ':'));
            return true;
        } catch (Throwable error) {
            RUNTIME_TARGET_BINDERS.remove(key, endpoint);
            Log.w("SimpleHook", "Runtime request Binder delivery failed; falling back to broadcast", error);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void cacheRuntimeTargetBinder(JSONObject stored, Bundle resultExtras) {
        if (resultExtras == null) return;
        IBinder endpoint = resultExtras.getBinder(RUNTIME_REQUEST_BINDER);
        if (endpoint == null) return;
        String key = runtimeTargetKey(stored);
        try {
            endpoint.linkToDeath(() -> {
                if (RUNTIME_TARGET_BINDERS.remove(key, endpoint)) {
                    Log.i("SimpleHook", "Runtime request Binder died; cache cleared: target="
                            + key.replace('\n', ':'));
                }
            }, 0);
        } catch (Throwable error) {
            RUNTIME_TARGET_BINDERS.remove(key, endpoint);
            Log.w("SimpleHook", "Runtime request Binder was already dead during cache", error);
            return;
        }
        RUNTIME_TARGET_BINDERS.put(key, endpoint);
        Log.i("SimpleHook", "Runtime request Binder cached: target=" + key.replace('\n', ':')
                + " actual_process=" + resultExtras.getString(RUNTIME_REQUEST_BINDER_PROCESS, ""));
    }

    private void scheduleRuntimeRequest(JSONObject stored) {
        final String requestId = stored.optString("request_id", "");
        if (requestId.isEmpty()) return;
        runtimeDeliveryAcknowledged.remove(requestId);
        for (int attempt = 0; attempt < RUNTIME_REQUEST_RETRY_DELAYS_MS.length; attempt++) {
            final int deliveryAttempt = attempt + 1;
            runtimeDeliveryHandler.postDelayed(
                    () -> broadcastRuntimeRequest(stored, requestId, deliveryAttempt),
                    RUNTIME_REQUEST_RETRY_DELAYS_MS[attempt]);
        }
        runtimeDeliveryHandler.postDelayed(
                () -> runtimeDeliveryAcknowledged.remove(requestId),
                RUNTIME_REQUEST_RETRY_DELAYS_MS[RUNTIME_REQUEST_RETRY_DELAYS_MS.length - 1] + 5_000L);
    }

    private void broadcastRuntimeRequest(JSONObject stored, String requestId, int attempt) {
        if (runtimeDeliveryAcknowledged.contains(requestId)) return;
        try {
            if (!runtimeRequests.isPending(requestId)) return;
            Intent intent = new Intent("com.luckylca.autocrack.runtime.REQUEST")
                    .setPackage(stored.getString("package"))
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("json", stored.toString());
            BroadcastReceiver acknowledgement = new BroadcastReceiver() {
                @Override public void onReceive(android.content.Context ignored, Intent ignoredIntent) {
                    if (getResultCode() == Activity.RESULT_OK) {
                        runtimeDeliveryAcknowledged.add(requestId);
                        cacheRuntimeTargetBinder(stored, getResultExtras(false));
                        Log.i("SimpleHook", "Runtime request delivery acknowledged: id=" + requestId
                                + " attempt=" + attempt);
                    } else {
                        Log.w("SimpleHook", "Runtime request delivery not acknowledged: id=" + requestId
                                + " attempt=" + attempt + " result=" + getResultCode());
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 34) {
                BroadcastOptions options = BroadcastOptions.makeBasic()
                        .setShareIdentityEnabled(true)
                        .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE);
                contextOrThrow().sendOrderedBroadcast(intent, null, options.toBundle(), acknowledgement,
                        runtimeDeliveryHandler, Activity.RESULT_CANCELED, null, null);
            } else {
                contextOrThrow().sendOrderedBroadcast(intent, null, acknowledgement, runtimeDeliveryHandler,
                        Activity.RESULT_CANCELED, null, null);
            }
        } catch (Throwable error) {
            Log.w("SimpleHook", "Runtime request broadcast failed: id=" + requestId
                    + " attempt=" + attempt, error);
        }
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
