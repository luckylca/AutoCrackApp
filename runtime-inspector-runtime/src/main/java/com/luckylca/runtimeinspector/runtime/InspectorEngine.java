package com.luckylca.runtimeinspector.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.luckylca.autocrack.runtime.shared.RuntimeDispatcher;
import de.robv.android.xposed.XposedBridge;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class InspectorEngine {
    private final Context context;
    private final InspectorChannel channel;
    private final String packageName;
    private final String processName;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Long> handledAt = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "AutoCrack-RuntimeWorker");
        thread.setDaemon(true);
        return thread;
    });
    private static final long HANDLED_REQUEST_TTL_MS = 120_000L;

    public InspectorEngine(Context context, String packageName, String processName) {
        this.context = context;
        this.channel = new InspectorChannel(context);
        this.packageName = packageName;
        this.processName = processName;
    }

    public void start() throws Throwable {
        WindowRootRegistry.install();
        channel.registerRequestReceiver(packageName, processName, this::enqueue);
        main.post(this::poll);
    }

    private void poll() {
        try {
            pruneHandledRequests();
            org.json.JSONArray requests = channel.pending(packageName, processName);
            for (int i = 0; i < requests.length(); i++) enqueue(requests.getJSONObject(i));
        } catch (Throwable error) {
            XposedBridge.log("RuntimeInspector poll failed: " + error);
        } finally {
            main.postDelayed(this::poll, 250L);
        }
    }

    private void enqueue(JSONObject request) throws Exception {
        String id = request.getString("request_id");
        if (handledAt.containsKey(id)) {
            XposedBridge.log("RuntimeInspector request duplicate already handled: id=" + id);
            return;
        }
        if (!inFlight.add(id)) {
            XposedBridge.log("RuntimeInspector request duplicate in flight: id=" + id);
            return;
        }
        XposedBridge.log("RuntimeInspector request accepted: id=" + id
                + " kind=" + request.optString("kind", ""));
        workers.execute(() -> {
            try { handle(request); }
            finally {
                handledAt.put(id, System.currentTimeMillis());
                inFlight.remove(id);
            }
        });
    }

    private void pruneHandledRequests() {
        long oldest = System.currentTimeMillis() - HANDLED_REQUEST_TTL_MS;
        handledAt.entrySet().removeIf(entry -> entry.getValue() < oldest);
    }

    private void handle(JSONObject request) {
        try {
            String id = request.getString("request_id");
            String nonce = request.getString("nonce");
            JSONObject result;
            try {
                result = RuntimeDispatcher.execute(context, request);
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
