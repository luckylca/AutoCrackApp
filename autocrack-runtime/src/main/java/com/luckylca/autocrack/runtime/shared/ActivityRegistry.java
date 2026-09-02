package com.luckylca.autocrack.runtime.shared;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One lifecycle observer for running Activity instances. */
public final class ActivityRegistry implements Application.ActivityLifecycleCallbacks {
    private static final ActivityRegistry INSTANCE = new ActivityRegistry();
    private final AtomicBoolean installed = new AtomicBoolean();
    private final AtomicLong order = new AtomicLong();
    private final Map<Activity, MutableRecord> states = new WeakHashMap<>();
    public static ActivityRegistry get() { return INSTANCE; }
    private ActivityRegistry() {}
    public void install(Application app) { if (installed.compareAndSet(false, true)) app.registerActivityLifecycleCallbacks(this); }
    public List<ActivitySnapshot> snapshot() {
        synchronized (states) {
            List<ActivitySnapshot> out = new ArrayList<>();
            for (Map.Entry<Activity, MutableRecord> e : states.entrySet()) if (e.getKey() != null) out.add(e.getValue().snapshot(e.getKey()));
            return out;
        }
    }
    private void state(Activity a, String s) {
        long now = System.currentTimeMillis();
        synchronized (states) {
            MutableRecord r = states.get(a);
            if (r == null) { r = new MutableRecord(now); states.put(a, r); }
            r.state = s;
            r.lastStateAt = now;
            r.eventOrder = order.incrementAndGet();
            r.eventCount++;
            if ("resumed".equals(s)) r.lastResumedAt = now;
            if ("paused".equals(s)) r.lastPausedAt = now;
            if ("stopped".equals(s)) r.lastStoppedAt = now;
        }
    }
    @Override public void onActivityCreated(Activity a, Bundle b) { state(a,"created"); }
    @Override public void onActivityStarted(Activity a) { state(a,"started"); }
    @Override public void onActivityResumed(Activity a) { state(a,"resumed"); }
    @Override public void onActivityPaused(Activity a) { state(a,"paused"); }
    @Override public void onActivityStopped(Activity a) { state(a,"stopped"); }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { state(a,"save_instance_state"); }
    @Override public void onActivityDestroyed(Activity a) { state(a,"destroyed"); synchronized (states) { states.remove(a); } }

    private static final class MutableRecord {
        final long firstSeenAt;
        String state;
        long lastStateAt;
        long lastResumedAt;
        long lastPausedAt;
        long lastStoppedAt;
        long eventOrder;
        int eventCount;
        MutableRecord(long firstSeenAt) { this.firstSeenAt = firstSeenAt; }
        ActivitySnapshot snapshot(Activity activity) {
            return new ActivitySnapshot(activity, state, firstSeenAt, lastStateAt,
                    lastResumedAt, lastPausedAt, lastStoppedAt, eventOrder, eventCount);
        }
    }

    public record ActivitySnapshot(Activity activity, String state, long firstSeenAt, long lastStateAt,
                                   long lastResumedAt, long lastPausedAt, long lastStoppedAt,
                                   long eventOrder, int eventCount) {}
}
