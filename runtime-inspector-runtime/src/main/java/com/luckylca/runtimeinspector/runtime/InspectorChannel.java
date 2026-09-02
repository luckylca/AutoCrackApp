package com.luckylca.runtimeinspector.runtime;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import com.luckylca.autocrack.runtime.shared.RuntimeRequestStore;
import de.robv.android.xposed.XSharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

final class InspectorChannel {
    static final String MODULE_PACKAGE = "com.luckylca.autocrack.runtime";
    static final String PREFS = RuntimeRequestStore.PREFS;
    static final String REQUESTS = RuntimeRequestStore.REQUESTS;
    static final String JSON = "json";
    static final Uri URI = Uri.parse("content://" + MODULE_PACKAGE);

    private final Context context;
    private final XSharedPreferences preferences;

    InspectorChannel(Context context) {
        this.context = context;
        this.preferences = new XSharedPreferences(MODULE_PACKAGE, PREFS);
    }

    JSONArray pending(String packageName, String processName) throws Exception {
        try {
            JSONObject request = new JSONObject().put("package", packageName);
            if (processName == null) request.put("process", JSONObject.NULL); else request.put("process", processName);
            Bundle extras = new Bundle();
            extras.putString(JSON, request.toString());
            Bundle raw = context.getContentResolver().call(URI, "runtime_pending", null, extras);
            String text = raw == null ? null : raw.getString(JSON);
            if (text != null) {
                JSONObject value = new JSONObject(text);
                if (value.optBoolean("ok")) return value.optJSONArray("requests") == null ? new JSONArray() : value.getJSONArray("requests");
                de.robv.android.xposed.XposedBridge.log("RuntimeInspector provider pending failed: " + value);
            }
        } catch (Throwable providerError) {
            de.robv.android.xposed.XposedBridge.log("RuntimeInspector provider pending unavailable, using XSharedPreferences: " + providerError);
        }
        preferences.reload();
        JSONObject requests = new JSONObject(preferences.getString(REQUESTS, "{}"));
        JSONArray selected = new JSONArray();
        java.util.Iterator<String> keys = requests.keys();
        while (keys.hasNext()) {
            JSONObject request = requests.getJSONObject(keys.next());
            if (!packageName.equals(request.optString("package"))) continue;
            String wantedProcess = request.isNull("process") ? null : request.optString("process", null);
            if (wantedProcess == null || wantedProcess.equals(processName)) selected.put(request);
        }
        return selected;
    }

    void sendResult(String requestId, String nonce, String packageName, String processName, JSONObject result) {
        try {
            JSONObject payload = new JSONObject()
                    .put("request_id", requestId)
                    .put("nonce", nonce)
                    .put("package", packageName)
                    .put("process", processName)
                    .put("pid", android.os.Process.myPid())
                    .put("result", result);
            Bundle extras = new Bundle();
            extras.putString(JSON, payload.toString());
            context.getContentResolver().call(URI, "runtime_complete", null, extras);
        } catch (Throwable error) {
            de.robv.android.xposed.XposedBridge.log("RuntimeInspector result broadcast failed: " + error);
        }
    }
}
