package com.luckylca.autocrack.runtime.shared;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One lifecycle observer for running Activity instances. */
public final class ActivityRegistry implements Application.ActivityLifecycleCallbacks {
    private static final ActivityRegistry INSTANCE = new ActivityRegistry();
    private final AtomicBoolean installed = new AtomicBoolean();
    private final Map<Activity, String> states = new WeakHashMap<>();
    public static ActivityRegistry get() { return INSTANCE; }
    private ActivityRegistry() {}
    public void install(Application app) { if (installed.compareAndSet(false, true)) app.registerActivityLifecycleCallbacks(this); }
    public List<ActivitySnapshot> snapshot() {
        synchronized (states) {
            List<ActivitySnapshot> out = new ArrayList<>();
            for (Map.Entry<Activity,String> e : states.entrySet()) if (e.getKey() != null) out.add(new ActivitySnapshot(e.getKey(), e.getValue()));
            return out;
        }
    }
    private void state(Activity a, String s) { synchronized (states) { states.put(a, s); } }
    @Override public void onActivityCreated(Activity a, Bundle b) { state(a,"created"); }
    @Override public void onActivityStarted(Activity a) { state(a,"started"); }
    @Override public void onActivityResumed(Activity a) { state(a,"resumed"); }
    @Override public void onActivityPaused(Activity a) { state(a,"paused"); }
    @Override public void onActivityStopped(Activity a) { state(a,"stopped"); }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) { synchronized (states) { states.remove(a); } }
    public record ActivitySnapshot(Activity activity, String state) {}
}
