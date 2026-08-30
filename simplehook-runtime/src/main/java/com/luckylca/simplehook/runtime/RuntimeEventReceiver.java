package com.luckylca.simplehook.runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.luckylca.simplehook.core.RuleState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

public final class RuntimeEventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!RuntimeChannel.ACTION.equals(intent.getAction())) return;
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
                    rules.setState(payload.getString("id"), RuleState.valueOf(payload.getString("state")),
                            payload.isNull("detail") ? null : payload.optString("detail", null));
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
                default -> throw new IllegalArgumentException("Unknown runtime event: " + event);
            }
        } catch (Throwable error) {
            android.util.Log.e("SimpleHook", "Runtime event rejected", error);
        }
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
