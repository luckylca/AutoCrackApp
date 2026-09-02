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
