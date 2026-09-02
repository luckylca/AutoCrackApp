package com.luckylca.runtimeinspector.runtime;

import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.UUID;
import org.json.JSONObject;

@SuppressLint({"ApplySharedPref", "WorldReadableFiles"})
public final class InspectorProvider extends ContentProvider {
    private static final String PREFS = InspectorChannel.PREFS;
    private static final String REQUESTS = InspectorChannel.REQUESTS;
    private static final String RESULTS = "results";
    private static final int MAX_REQUESTS = 64;
    private SharedPreferences preferences;

    @Override
    public boolean onCreate() {
        preferences = requireContext().getSharedPreferences(PREFS, Context.MODE_WORLD_READABLE);
        return true;
    }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        try {
            JSONObject request = decode(argument, extras);
            JSONObject result = switch (method) {
                case "submit" -> { authorizeShell(); yield submit(request); }
                case "result" -> { authorizeShell(); yield result(request.getString("request_id")); }
                case "status" -> { authorizeShell(); yield status(); }
                case "clear" -> { authorizeShell(); yield clear(request); }
                case "complete" -> { authorizeRuntime(request); yield complete(request); }
                default -> error("UNKNOWN_METHOD", "Unknown method: " + method);
            };
            return bundle(result);
        } catch (SecurityException error) {
            return bundle(error("ACCESS_DENIED", error.getMessage()));
        } catch (Throwable error) {
            return bundle(error("RUNTIME_ERROR", error.toString()));
        }
    }

    private synchronized JSONObject submit(JSONObject input) throws Exception {
        String packageName = input.optString("package", "").trim();
        String kind = input.optString("kind", "").trim();
        if (packageName.isEmpty()) return error("PACKAGE_REQUIRED", "package is required");
        if (!InspectorPrimitives.supports(kind)) return error("UNSUPPORTED_KIND", "Unsupported kind: " + kind);
        JSONObject requests = object(REQUESTS);
        if (requests.length() >= MAX_REQUESTS) return error("QUEUE_FULL", "Too many pending requests");
        String id = UUID.randomUUID().toString();
        JSONObject stored = new JSONObject(input.toString())
                .put("request_id", id)
                .put("nonce", UUID.randomUUID().toString())
                .put("created_at", System.currentTimeMillis());
        requests.put(id, stored);
        save(REQUESTS, requests);
        return ok().put("request_id", id);
    }

    private synchronized JSONObject result(String id) throws Exception {
        JSONObject results = object(RESULTS);
        JSONObject value = results.optJSONObject(id);
        if (value != null) {
            results.remove(id);
            save(RESULTS, results);
            return ok().put("pending", false).put("result", value);
        }
        JSONObject pendingRequest = object(REQUESTS).optJSONObject(id);
        boolean pending = pendingRequest != null;
        return ok().put("pending", pending).put("missing", !pending);
    }

    private synchronized JSONObject complete(JSONObject input) throws Exception {
        String id = input.getString("request_id");
        JSONObject requests = object(REQUESTS);
        JSONObject original = requests.optJSONObject(id);
        if (original == null) return error("REQUEST_NOT_FOUND", "Request not found: " + id);
        String packageName = input.getString("package");
        if (!packageName.equals(original.optString("package"))) {
            throw new SecurityException("Completion package does not match request");
        }
        String expectedNonce = original.optString("nonce", null);
        String actualNonce = input.optString("nonce", null);
        if (expectedNonce == null || !expectedNonce.equals(actualNonce)) {
            throw new SecurityException("Completion nonce does not match request");
        }
        requests.remove(id);
        JSONObject completed = input.getJSONObject("result");
        completed.put("runtime_package", packageName)
                .put("runtime_process", input.optString("process", JSONObject.NULL.toString()))
                .put("runtime_pid", input.optInt("pid", -1))
                .put("completed_at", System.currentTimeMillis());
        JSONObject results = object(RESULTS);
        results.put(id, completed);
        preferences.edit().putString(REQUESTS, requests.toString()).putString(RESULTS, results.toString()).commit();
        return ok();
    }

    private synchronized JSONObject clear(JSONObject input) throws Exception {
        String packageName = input.optString("package", null);
        if (packageName == null || packageName.isBlank()) {
            preferences.edit().putString(REQUESTS, "{}").putString(RESULTS, "{}").commit();
            return ok().put("cleared", "all");
        }
        JSONObject requests = object(REQUESTS);
        Iterator<String> keys = requests.keys();
        java.util.List<String> remove = new java.util.ArrayList<>();
        while (keys.hasNext()) {
            String id = keys.next();
            if (packageName.equals(requests.getJSONObject(id).optString("package"))) remove.add(id);
        }
        remove.forEach(requests::remove);
        save(REQUESTS, requests);
        return ok().put("cleared", remove.size());
    }

    private synchronized JSONObject status() throws Exception {
        return ok().put("version", "0.1.0")
                .put("pending_requests", object(REQUESTS).length())
                .put("pending_results", object(RESULTS).length());
    }

    private void authorizeShell() {
        int uid = Binder.getCallingUid();
        if (uid != 0 && uid != Process.SHELL_UID && uid != Process.myUid()) {
            throw new SecurityException("Operation requires root or shell");
        }
    }

    private void authorizeRuntime(JSONObject input) {
        int uid = Binder.getCallingUid();
        if (uid == 0 || uid == Process.SHELL_UID || uid == Process.myUid()) return;
        String claimed = input.optString("package", null);
        String[] packages = requireContext().getPackageManager().getPackagesForUid(uid);
        if (claimed == null || packages == null || !Arrays.asList(packages).contains(claimed)) {
            throw new SecurityException("Runtime may only complete its own package request");
        }
    }

    private JSONObject object(String key) throws Exception {
        return new JSONObject(preferences.getString(key, "{}"));
    }

    private void save(String key, JSONObject value) {
        preferences.edit().putString(key, value.toString()).commit();
    }

    private static JSONObject decode(String argument, Bundle extras) throws Exception {
        if (extras != null && extras.containsKey("json")) return new JSONObject(extras.getString("json", "{}"));
        if (extras != null && extras.containsKey("base64")) {
            byte[] raw = Base64.decode(extras.getString("base64", ""), Base64.DEFAULT);
            return new JSONObject(new String(raw, StandardCharsets.UTF_8));
        }
        return argument == null || argument.isBlank() ? new JSONObject() : new JSONObject(argument);
    }

    private static Bundle bundle(JSONObject json) {
        Bundle bundle = new Bundle();
        bundle.putString("json", json.toString());
        return bundle;
    }

    private static JSONObject ok() {
        try { return new JSONObject().put("ok", true); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }

    private static JSONObject error(String code, String message) {
        try { return new JSONObject().put("ok", false).put("error", new JSONObject().put("code", code).put("message", message)); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }

    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
