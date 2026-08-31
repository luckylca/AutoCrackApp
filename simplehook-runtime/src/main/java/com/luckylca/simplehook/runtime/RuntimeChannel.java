package com.luckylca.simplehook.runtime;

import android.app.BroadcastOptions;
import android.app.Activity;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import de.robv.android.xposed.XSharedPreferences;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class RuntimeChannel {
    static final String ACTION = "com.luckylca.simplehook.runtime.EVENT";
    static final String EVENT = "event";
    static final String JSON = "json";
    static final String TOKEN = "token";
    static final String EVENT_ID = "event_id";
    private static final String MODULE_PACKAGE = "com.luckylca.simplehook.runtime";
    private static final String PREF_FILE = "simplehook_rules";
    private static final int MAX_PENDING_EVENTS = 256;
    private static final int MAX_DELIVERY_ATTEMPTS = 10;
    private static final long RETRY_INTERVAL_MS = 1_000L;
    private final Context context;
    private final XSharedPreferences preferences;
    private final Map<String, PendingEvent> pending = new ConcurrentHashMap<>();

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
        if ("heartbeat".equals(event)) {
            retryPending();
            dispatch(null, event, payload.toString());
            return;
        }
        if (pending.size() >= MAX_PENDING_EVENTS) return;
        String id = UUID.randomUUID().toString();
        PendingEvent queued = new PendingEvent(event, payload.toString());
        pending.put(id, queued);
        dispatch(id, queued.event, queued.json);
    }

    private void retryPending() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, PendingEvent> entry : pending.entrySet()) {
            PendingEvent event = entry.getValue();
            if (event.attempts >= MAX_DELIVERY_ATTEMPTS) {
                pending.remove(entry.getKey(), event);
            } else if (now - event.lastAttemptMs >= RETRY_INTERVAL_MS) {
                dispatch(entry.getKey(), event.event, event.json);
            }
        }
    }

    private void dispatch(String id, String event, String json) {
        preferences.reload();
        Intent intent = new Intent(ACTION)
                .setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".RuntimeEventReceiver"))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(EVENT, event)
                .putExtra(JSON, json)
                .putExtra(TOKEN, preferences.getString(RuleStore.CHANNEL_TOKEN, ""));
        if (id != null) {
            PendingEvent queued = pending.get(id);
            if (queued == null) return;
            queued.attempts++;
            queued.lastAttemptMs = System.currentTimeMillis();
            intent.putExtra(EVENT_ID, id);
        }
        BroadcastReceiver completion = id == null ? null : new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent ignoredIntent) {
                if (getResultCode() == Activity.RESULT_OK) pending.remove(id);
            }
        };
        if (Build.VERSION.SDK_INT >= 34) {
            BroadcastOptions options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE);
            context.sendOrderedBroadcast(intent, null, options.toBundle(), completion,
                    null, Activity.RESULT_CANCELED, null, null);
        } else {
            context.sendOrderedBroadcast(intent, null, completion,
                    null, Activity.RESULT_CANCELED, null, null);
        }
    }

    private static final class PendingEvent {
        final String event;
        final String json;
        volatile int attempts;
        volatile long lastAttemptMs;

        PendingEvent(String event, String json) {
            this.event = event;
            this.json = json;
        }
    }
}
