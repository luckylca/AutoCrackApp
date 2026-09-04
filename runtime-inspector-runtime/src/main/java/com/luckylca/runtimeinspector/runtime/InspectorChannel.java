package com.luckylca.runtimeinspector.runtime;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import com.luckylca.autocrack.runtime.shared.RuntimeRequestStore;
import de.robv.android.xposed.XSharedPreferences;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class InspectorChannel {
    static final String MODULE_PACKAGE = "com.luckylca.autocrack.runtime";
    static final String PREFS = RuntimeRequestStore.PREFS;
    static final String REQUESTS = RuntimeRequestStore.REQUESTS;
    static final String JSON = "json";
    static final Uri URI = Uri.parse("content://" + MODULE_PACKAGE);
    private static final String ACTION_REQUEST = "com.luckylca.autocrack.runtime.REQUEST";
    private static final String ACTION_EVENT = "com.luckylca.autocrack.runtime.EVENT";
    private static final String EVENT = "event";
    private static final String TOKEN = "token";
    private static final String EVENT_ID = "event_id";
    private static final String EVENT_PREFS = "simplehook_rules";
    private static final String CHANNEL_TOKEN = "channel_token";
    private static final String EVENT_RECEIVER = "com.luckylca.simplehook.runtime.RuntimeEventReceiver";
    private static final String REQUEST_BINDER = "runtime_request_binder";
    private static final String REQUEST_BINDER_PROCESS = "runtime_request_binder_process";
    private static final String REQUEST_BINDER_DESCRIPTOR = "com.luckylca.autocrack.runtime.REQUEST_ENDPOINT";
    private static final int REQUEST_BINDER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;

    private final Context context;
    private final XSharedPreferences preferences;
    private final XSharedPreferences eventPreferences;
    private volatile boolean requestReceiverRegistered;
    private volatile int moduleUid = -1;
    private volatile IBinder requestEndpoint;

    InspectorChannel(Context context) {
        this.context = context;
        this.preferences = new XSharedPreferences(MODULE_PACKAGE, PREFS);
        this.eventPreferences = new XSharedPreferences(MODULE_PACKAGE, EVENT_PREFS);
    }

    void registerRequestReceiver(String packageName, String processName, RequestHandler handler) {
        if (requestReceiverRegistered) return;
        if (Build.VERSION.SDK_INT < 34) {
            de.robv.android.xposed.XposedBridge.log("RuntimeInspector request broadcast receiver disabled before API 34; using provider/XSharedPreferences polling");
            return;
        }
        requestEndpoint = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(REQUEST_BINDER_DESCRIPTOR);
                    return true;
                }
                if (code != REQUEST_BINDER_TRANSACTION) return super.onTransact(code, data, reply, flags);
                try {
                    data.enforceInterface(REQUEST_BINDER_DESCRIPTOR);
                    int expectedUid = moduleUid;
                    if (expectedUid < 0 || Binder.getCallingUid() != expectedUid) {
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeInt(0);
                            reply.writeString("Runtime request Binder caller UID is not the verified module UID");
                        }
                        return true;
                    }
                    String raw = data.readString();
                    JSONObject request = new JSONObject(raw == null ? "{}" : raw);
                    if (!packageName.equals(request.optString("package"))) {
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeInt(0);
                            reply.writeString("Runtime request Binder package does not match target");
                        }
                        return true;
                    }
                    String wantedProcess = request.isNull("process") ? null : request.optString("process", null);
                    if (wantedProcess != null && !wantedProcess.equals(processName)) {
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeInt(0);
                            reply.writeString("Runtime request Binder process does not match target");
                        }
                        return true;
                    }
                    handler.handle(request);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(1);
                        reply.writeString("");
                    }
                    return true;
                } catch (Throwable error) {
                    de.robv.android.xposed.XposedBridge.log("RuntimeInspector request Binder rejected: " + error);
                    if (reply != null) {
                        reply.writeNoException();
                        reply.writeInt(0);
                        reply.writeString(error.toString());
                    }
                    return true;
                }
            }
        };
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (!ACTION_REQUEST.equals(intent.getAction())) return;
                try {
                    moduleUid = verifyModuleSender(this);
                    JSONObject request = new JSONObject(intent.getStringExtra(JSON));
                    if (!packageName.equals(request.optString("package"))) return;
                    String wantedProcess = request.isNull("process") ? null : request.optString("process", null);
                    if (wantedProcess != null && !wantedProcess.equals(processName)) return;
                    handler.handle(request);
                    if (isOrderedBroadcast()) {
                        Bundle resultExtras = getResultExtras(true);
                        resultExtras.putBinder(REQUEST_BINDER, requestEndpoint);
                        resultExtras.putString(REQUEST_BINDER_PROCESS, processName);
                        setResultExtras(resultExtras);
                        setResultCode(Activity.RESULT_OK);
                    }
                } catch (Throwable error) {
                    if (isOrderedBroadcast()) setResultCode(Activity.RESULT_CANCELED);
                    de.robv.android.xposed.XposedBridge.log("RuntimeInspector request broadcast rejected: " + error);
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(ACTION_REQUEST), Context.RECEIVER_EXPORTED);
        requestReceiverRegistered = true;
        de.robv.android.xposed.XposedBridge.log("RuntimeInspector request broadcast receiver installed: " + packageName + ":" + processName);
    }

    JSONArray pending(String packageName, String processName) throws Exception {
        try {
            JSONObject request = new JSONObject().put("package", packageName);
            if (processName == null) request.put("process", JSONObject.NULL); else request.put("process", processName);
            Bundle extras = new Bundle(); extras.putString(JSON, request.toString());
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
        JSONObject payload;
        try {
            payload = new JSONObject()
                    .put("request_id", requestId).put("nonce", nonce).put("package", packageName)
                    .put("process", processName).put("pid", android.os.Process.myPid()).put("result", result);
        } catch (Throwable error) {
            de.robv.android.xposed.XposedBridge.log("RuntimeInspector result payload build failed: " + error);
            return;
        }
        try {
            Bundle extras = new Bundle(); extras.putString(JSON, payload.toString());
            context.getContentResolver().call(URI, "runtime_complete", null, extras);
            return;
        } catch (Throwable providerError) {
            de.robv.android.xposed.XposedBridge.log("RuntimeInspector provider complete unavailable, using event broadcast: " + providerError);
        }
        sendRuntimeCompleteEvent(payload);
    }

    private void sendRuntimeCompleteEvent(JSONObject payload) {
        try {
            eventPreferences.reload();
            String token = eventPreferences.getString(CHANNEL_TOKEN, "");
            if (token == null || token.isEmpty()) throw new SecurityException("Runtime event token unavailable");
            Intent intent = new Intent(ACTION_EVENT)
                    .setComponent(new ComponentName(MODULE_PACKAGE, EVENT_RECEIVER))
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(EVENT, "runtime_complete")
                    .putExtra(JSON, payload.toString())
                    .putExtra(TOKEN, token)
                    .putExtra(EVENT_ID, UUID.randomUUID().toString());
            if (Build.VERSION.SDK_INT >= 34) {
                BroadcastOptions options = BroadcastOptions.makeBasic()
                        .setShareIdentityEnabled(true)
                        .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE);
                context.sendOrderedBroadcast(intent, null, options.toBundle(), null, null, Activity.RESULT_CANCELED, null, null);
            } else {
                context.sendOrderedBroadcast(intent, null, null, null, Activity.RESULT_CANCELED, null, null);
            }
        } catch (Throwable error) {
            de.robv.android.xposed.XposedBridge.log("RuntimeInspector result event broadcast failed: " + error);
        }
    }

    private int verifyModuleSender(BroadcastReceiver receiver) {
        if (Build.VERSION.SDK_INT < 34) {
            throw new SecurityException("Runtime request sender identity requires API 34+");
        }
        int uid = receiver.getSentFromUid();
        String[] packages = uid < 0 ? null : context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) throw new SecurityException("Runtime request sender package unavailable");
        for (String item : packages) if (MODULE_PACKAGE.equals(item)) return uid;
        throw new SecurityException("Runtime request sender is not AutoCrack Runtime");
    }

    interface RequestHandler { void handle(JSONObject request) throws Exception; }
}
