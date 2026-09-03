package com.luckylca.simplehook.runtime;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.luckylca.simplehook.core.RuleState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.json.JSONObject;

public final class RuntimeEventReceiver extends BroadcastReceiver {
    private static final int MAX_RECENT_EVENTS = 512;
    private static final Set<String> RECENT_EVENTS = ConcurrentHashMap.newKeySet();
    private static final Queue<String> RECENT_ORDER = new ConcurrentLinkedQueue<>();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!RuntimeChannel.ACTION.equals(intent.getAction())) return;
        String eventId = intent.getStringExtra(RuntimeChannel.EVENT_ID);
        if (eventId != null && !RECENT_EVENTS.add(eventId)) {
            acknowledge(true);
            return;
        }
        try {
            JSONObject payload = new JSONObject(intent.getStringExtra(RuntimeChannel.JSON));
            RuleStore rules = new RuleStore(context);
            verifyToken(rules.channelToken(), intent.getStringExtra(RuntimeChannel.TOKEN));
            verifyCaller(context, payload.optString("package", null));
            String event = intent.getStringExtra(RuntimeChannel.EVENT);
            switch (event) {
                case "heartbeat" -> SimpleHookProvider.recordHeartbeat(payload);
                case "state" -> {
                    JSONObject rule = rules.find(payload.getString("id"));
                    requirePackage(rule, payload.getString("package"), "rule");
                    rules.setRuntimeState(payload.getString("id"), RuleState.valueOf(payload.getString("state")),
                            payload.isNull("detail") ? null : payload.optString("detail", null),
                            payload.optLong("event_order", 0L), payload.optLong("generation", 0L));
                }
                case "log" -> {
                    JSONObject entry = payload.getJSONObject("entry");
                    requirePackage(entry, payload.getString("package"), "log entry");
                    new JsonLogStore(context).append(entry);
                }
                case "inspect_complete" -> {
                    JSONObject request = rules.inspectionRequest(payload.getString("request_id"));
                    requirePackage(request, payload.getString("package"), "inspection request");
                    rules.completeInspection(payload.getString("request_id"), payload.getJSONObject("result"));
                }
                case "runtime_complete" -> new com.luckylca.autocrack.runtime.shared.RuntimeRequestStore(context).complete(payload);
                default -> throw new IllegalArgumentException("Unknown runtime event: " + event);
            }
            remember(eventId);
            acknowledge(true);
        } catch (Throwable error) {
            if (eventId != null) RECENT_EVENTS.remove(eventId);
            acknowledge(false);
            android.util.Log.e("SimpleHook", "Runtime event rejected", error);
        }
    }

    private void remember(String eventId) {
        if (eventId == null) return;
        RECENT_ORDER.add(eventId);
        while (RECENT_ORDER.size() > MAX_RECENT_EVENTS) {
            String expired = RECENT_ORDER.poll();
            if (expired != null) RECENT_EVENTS.remove(expired);
        }
    }

    private void acknowledge(boolean accepted) {
        if (isOrderedBroadcast()) setResultCode(accepted ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
    }

    private static void verifyToken(String expected, String actual) {
        if (expected == null || expected.isEmpty() || actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Runtime event token is invalid");
        }
    }

    private void verifyCaller(Context context, String claimedPackage) {
        if (Build.VERSION.SDK_INT < 34) return;
        int uid = getSentFromUid();
        String[] packages = uid < 0 ? null : context.getPackageManager().getPackagesForUid(uid);
        if (claimedPackage == null || packages == null
                || !java.util.Arrays.asList(packages).contains(claimedPackage)) {
            throw new SecurityException("Runtime event package does not match sender UID");
        }
    }

    private static void requirePackage(JSONObject value, String packageName, String label) {
        if (value == null || !packageName.equals(value.optString("package", null))) {
            throw new SecurityException("Runtime event does not match " + label + " package");
        }
    }
}
