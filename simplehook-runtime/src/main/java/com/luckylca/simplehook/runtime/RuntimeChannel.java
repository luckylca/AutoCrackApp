package com.luckylca.simplehook.runtime;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.app.BroadcastOptions;
import android.os.Build;
import de.robv.android.xposed.XSharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class RuntimeChannel {
    static final String ACTION = "com.luckylca.simplehook.runtime.EVENT";
    static final String EVENT = "event";
    static final String JSON = "json";
    static final String TOKEN = "token";
    private static final String MODULE_PACKAGE = "com.luckylca.simplehook.runtime";
    private static final String PREF_FILE = "simplehook_rules";
    private final Context context;
    private final XSharedPreferences preferences;

    RuntimeChannel(Context context) {
        this.context = context;
        preferences = new XSharedPreferences(MODULE_PACKAGE, PREF_FILE);
    }

    JSONObject rulesForPackage(String packageName, String processName) throws JSONException {
        preferences.reload();
        JSONArray all = new JSONArray(preferences.getString("rules", "[]"));
        JSONArray selected = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject rule = all.getJSONObject(i);
            if (!packageName.equals(rule.optString("package")) || !rule.optBoolean("enabled", true)) continue;
            String process = rule.isNull("process") ? null : rule.optString("process", null);
            if (process == null || process.equals(processName)) selected.put(rule);
        }
        return new JSONObject().put("ok", true)
                .put("generation", preferences.getLong("generation", 0L))
                .put("rules", selected);
    }

    JSONArray pendingInspections(String packageName) throws JSONException {
        preferences.reload();
        JSONObject requests = new JSONObject(preferences.getString("inspection_requests", "{}"));
        JSONArray selected = new JSONArray();
        java.util.Iterator<String> keys = requests.keys();
        while (keys.hasNext()) {
            JSONObject request = requests.getJSONObject(keys.next());
            if (packageName.equals(request.optString("package"))) selected.put(request);
        }
        return selected;
    }

    void send(String event, JSONObject payload) {
        preferences.reload();
        Intent intent = new Intent(ACTION)
                .setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".RuntimeEventReceiver"))
                .putExtra(EVENT, event)
                .putExtra(JSON, payload.toString())
                .putExtra(TOKEN, preferences.getString(RuleStore.CHANNEL_TOKEN, ""));
        if (Build.VERSION.SDK_INT >= 34) {
            android.os.Bundle options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle();
            context.sendBroadcast(intent, null, options);
        } else {
            context.sendBroadcast(intent);
        }
    }
}
