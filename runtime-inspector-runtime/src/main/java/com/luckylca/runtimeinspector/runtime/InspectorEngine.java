package com.luckylca.runtimeinspector.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XposedBridge;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

final class InspectorEngine {
    private final Context context;
    private final InspectorChannel channel;
    private final String packageName;
    private final String processName;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    InspectorEngine(Context context, String packageName, String processName) {
        this.context = context;
        this.channel = new InspectorChannel(context);
        this.packageName = packageName;
        this.processName = processName;
    }

    void start() throws Throwable {
        WindowRootRegistry.install();
        main.post(this::poll);
    }

    private void poll() {
        try {
            org.json.JSONArray requests = channel.pending(packageName, processName);
            for (int i = 0; i < requests.length(); i++) {
                JSONObject request = requests.getJSONObject(i);
                String id = request.getString("request_id");
                if (!inFlight.add(id)) continue;
                handle(request);
                main.postDelayed(() -> inFlight.remove(id), 1_000L);
            }
        } catch (Throwable error) {
            XposedBridge.log("RuntimeInspector poll failed: " + error);
        } finally {
            main.postDelayed(this::poll, 250L);
        }
    }

    private void handle(JSONObject request) {
        try {
            String id = request.getString("request_id");
            String nonce = request.getString("nonce");
            JSONObject result;
            try {
                result = InspectorPrimitives.execute(request);
            } catch (Throwable error) {
                result = error("INSPECT_FAILED", error.toString());
            }
            channel.sendResult(id, nonce, packageName, processName, result);
        } catch (Throwable error) {
            XposedBridge.log("RuntimeInspector request failed: " + error);
        }
    }

    private static JSONObject error(String code, String message) {
        try {
            return new JSONObject().put("ok", false)
                    .put("error", new JSONObject().put("code", code).put("message", message));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
