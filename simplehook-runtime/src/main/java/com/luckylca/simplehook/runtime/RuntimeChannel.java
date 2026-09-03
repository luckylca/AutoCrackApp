package com.luckylca.simplehook.runtime;

import android.app.BroadcastOptions;
import android.app.Activity;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import de.robv.android.xposed.XSharedPreferences;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class RuntimeChannel {
    static final String ACTION = "com.luckylca.autocrack.runtime.EVENT";
    static final String ACTION_RULES = "com.luckylca.autocrack.runtime.RULES";
    static final String EVENT = "event";
    static final String JSON = "json";
    static final String TOKEN = "token";
    static final String EVENT_ID = "event_id";
    static final String CHANNEL_TOKEN = "channel_token";
    private static final String MODULE_PACKAGE = "com.luckylca.autocrack.runtime";
    private static final String PREF_FILE = "simplehook_rules";
    private static final int MAX_PENDING_EVENTS = 256;
    private static final int MAX_DELIVERY_ATTEMPTS = 10;
    private static final long RETRY_INTERVAL_MS = 1_000L;
    private final Context context;
    private final XSharedPreferences preferences;
    private final Map<String, PendingEvent> pending = new ConcurrentHashMap<>();
    private volatile String eventTokenOverride;
    private volatile boolean rulesReceiverRegistered;

    RuntimeChannel(Context context) {
        this.context = context;
        preferences = new XSharedPreferences(MODULE_PACKAGE, PREF_FILE);
    }


    void registerRulesReceiver(String packageName, String processName, RulesHandler handler) {
        if (rulesReceiverRegistered) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                if (!ACTION_RULES.equals(intent.getAction())) return;
                try {
                    verifyModuleSender(this);
                    JSONObject payload = new JSONObject(intent.getStringExtra(JSON));
                    if (!packageName.equals(payload.optString("package"))) return;
                    String wantedProcess = payload.isNull("process") ? null : payload.optString("process", null);
                    if (wantedProcess != null && !wantedProcess.equals(processName)) return;
                    String token = payload.optString(CHANNEL_TOKEN, "");
                    if (!token.isEmpty()) eventTokenOverride = token;
                    handler.handle(payload);
                } catch (Throwable error) {
                    de.robv.android.xposed.XposedBridge.log("SimpleHook rules broadcast rejected: " + error);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_RULES);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        rulesReceiverRegistered = true;
        de.robv.android.xposed.XposedBridge.log("SimpleHook rules broadcast receiver installed: "
                + packageName + ":" + processName);
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
                .put("source", "xsharedprefs")
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
        String token = eventTokenOverride;
        if (token == null || token.isEmpty()) token = preferences.getString(RuleStore.CHANNEL_TOKEN, "");
        Intent intent = new Intent(ACTION)
                .setComponent(new ComponentName(MODULE_PACKAGE, "com.luckylca.simplehook.runtime.RuntimeEventReceiver"))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(EVENT, event)
                .putExtra(JSON, json)
                .putExtra(TOKEN, token);
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

    private void verifyModuleSender(BroadcastReceiver receiver) {
        if (Build.VERSION.SDK_INT < 34) return;
        int uid = receiver.getSentFromUid();
        String[] packages = uid < 0 ? null : context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) throw new SecurityException("SimpleHook rules sender package unavailable");
        for (String item : packages) if (MODULE_PACKAGE.equals(item)) return;
        throw new SecurityException("SimpleHook rules sender is not AutoCrack Runtime");
    }

    interface RulesHandler { void handle(JSONObject payload) throws Exception; }

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
