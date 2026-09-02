package com.luckylca.autocrack.runtime.shared;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;

/** Provider-side half of the single target-process request/result channel. */
@SuppressLint("ApplySharedPref")
public final class RuntimeRequestStore {
    public static final String PREFS = "autocrack_runtime";
    public static final String REQUESTS = "requests";
    public static final String RESULTS = "results";
    private static final int MAX_REQUESTS = 128;
    private static final long MAX_REQUEST_AGE_MS = 60_000L;
    private final SharedPreferences preferences;

    public RuntimeRequestStore(Context context) {
        // LSPosed's xposedsharedprefs bridge exposes this MODE_PRIVATE file to scoped
        // XSharedPreferences readers without using the forbidden world-readable mode.
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.contains("initialized")) preferences.edit().putBoolean("initialized", true).commit();
    }

    public synchronized JSONObject submit(JSONObject input) throws Exception {
        String packageName = input.optString("package", "").trim();
        String kind = input.optString("kind", "").trim();
        if (packageName.isEmpty()) return error("PACKAGE_REQUIRED", "package is required");
        if (kind.isEmpty()) return error("KIND_REQUIRED", "kind is required");
        prune();
        JSONObject requests = object(REQUESTS);
        if (requests.length() >= MAX_REQUESTS) return error("QUEUE_FULL", "Too many pending runtime requests");
        String id = UUID.randomUUID().toString();
        JSONObject stored = new JSONObject(input.toString())
                .put("request_id", id)
                .put("nonce", UUID.randomUUID().toString())
                .put("created_at", System.currentTimeMillis());
        requests.put(id, stored);
        save(REQUESTS, requests);
        return ok().put("request_id", id);
    }

    public synchronized JSONObject pending(String packageName, String processName) throws Exception {
        prune();
        JSONObject requests = object(REQUESTS);
        org.json.JSONArray selected = new org.json.JSONArray();
        Iterator<String> keys = requests.keys();
        while (keys.hasNext()) {
            JSONObject request = requests.getJSONObject(keys.next());
            if (!packageName.equals(request.optString("package"))) continue;
            String wantedProcess = request.isNull("process") ? null : request.optString("process", null);
            if (wantedProcess == null || wantedProcess.equals(processName)) selected.put(request);
        }
        return ok().put("requests", selected);
    }

    public synchronized JSONObject result(String id) throws Exception {
        prune();
        JSONObject results = object(RESULTS);
        JSONObject value = results.optJSONObject(id);
        if (value != null) {
            results.remove(id);
            save(RESULTS, results);
            return ok().put("pending", false).put("result", value);
        }
        boolean pending = object(REQUESTS).has(id);
        return ok().put("pending", pending).put("missing", !pending);
    }

    public synchronized JSONObject complete(JSONObject input) throws Exception {
        String id = input.getString("request_id");
        JSONObject requests = object(REQUESTS);
        JSONObject original = requests.optJSONObject(id);
        if (original == null) return error("REQUEST_NOT_FOUND", "Request not found: " + id);
        String packageName = input.getString("package");
        if (!packageName.equals(original.optString("package"))) throw new SecurityException("Completion package does not match request");
        String expectedNonce = original.optString("nonce", null);
        if (expectedNonce == null || !expectedNonce.equals(input.optString("nonce", null))) throw new SecurityException("Completion nonce does not match request");
        String wantedProcess = original.isNull("process") ? null : original.optString("process", null);
        if (wantedProcess != null && !wantedProcess.equals(input.optString("process", null))) throw new SecurityException("Completion process does not match request");
        requests.remove(id);
        JSONObject completed = new JSONObject(input.getJSONObject("result").toString())
                .put("runtime_package", packageName)
                .put("runtime_process", input.optString("process", ""))
                .put("runtime_pid", input.optInt("pid", -1))
                .put("completed_at", System.currentTimeMillis());
        JSONObject results = object(RESULTS);
        results.put(id, completed);
        preferences.edit().putString(REQUESTS, requests.toString()).putString(RESULTS, results.toString()).commit();
        return ok();
    }

    public synchronized JSONObject clear(JSONObject input) throws Exception {
        String packageName = input.optString("package", null);
        if (packageName == null || packageName.isBlank()) {
            preferences.edit().putString(REQUESTS, "{}").putString(RESULTS, "{}").commit();
            return ok().put("cleared", "all");
        }
        JSONObject requests = object(REQUESTS); List<String> remove = new ArrayList<>();
        Iterator<String> keys = requests.keys();
        while (keys.hasNext()) { String id = keys.next(); if (packageName.equals(requests.getJSONObject(id).optString("package"))) remove.add(id); }
        remove.forEach(requests::remove); save(REQUESTS, requests);
        return ok().put("cleared", remove.size());
    }

    public synchronized JSONObject status() throws Exception {
        prune();
        return ok().put("version", "1.0.0")
                .put("authority", "com.luckylca.autocrack.runtime")
                .put("pending_requests", object(REQUESTS).length())
                .put("pending_results", object(RESULTS).length())
                .put("max_requests", MAX_REQUESTS);
    }

    private void prune() throws Exception {
        long oldest = System.currentTimeMillis() - MAX_REQUEST_AGE_MS;
        JSONObject requests = object(REQUESTS); List<String> expired = new ArrayList<>();
        Iterator<String> keys = requests.keys();
        while (keys.hasNext()) { String id = keys.next(); if (requests.getJSONObject(id).optLong("created_at", 0L) < oldest) expired.add(id); }
        if (!expired.isEmpty()) { expired.forEach(requests::remove); save(REQUESTS, requests); }
    }
    private JSONObject object(String key) throws Exception { return new JSONObject(preferences.getString(key, "{}")); }
    private void save(String key, JSONObject value) { preferences.edit().putString(key, value.toString()).commit(); }
    public static JSONObject ok() { try { return new JSONObject().put("ok", true); } catch (Exception e) { throw new AssertionError(e); } }
    public static JSONObject error(String code, String message) { try { return new JSONObject().put("ok", false).put("error", new JSONObject().put("code", code).put("message", message)); } catch (Exception e) { throw new AssertionError(e); } }
}
