package com.luckylca.autocrack.runtime.shared;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Explicit target-process threading boundary for Android UI/runtime capabilities. */
public final class RuntimeThreading {
    public static final long MAIN_CALL_TIMEOUT_MS = 5_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private RuntimeThreading() {}

    public static JSONObject callOnMain(Callable<JSONObject> action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return action.call();
            } catch (Throwable error) {
                return error("MAIN_THREAD_ACTION_FAILED", error.toString());
            }
        }
        FutureTask<JSONObject> task = new FutureTask<>(action);
        if (!MAIN.post(task)) return error("MAIN_THREAD_UNAVAILABLE", "Unable to enqueue work on the main looper");
        try {
            return task.get(MAIN_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            task.cancel(false);
            return error("MAIN_THREAD_TIMEOUT", "Main-looper action exceeded " + MAIN_CALL_TIMEOUT_MS + "ms");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return error("MAIN_THREAD_INTERRUPTED", interrupted.toString());
        } catch (Throwable error) {
            return error("MAIN_THREAD_ACTION_FAILED", error.toString());
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
