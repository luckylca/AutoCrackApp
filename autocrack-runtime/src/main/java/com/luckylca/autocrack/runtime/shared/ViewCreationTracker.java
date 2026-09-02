package com.luckylca.autocrack.runtime.shared;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded pre-instrumentation required for construction/inflate/add stacks. */
public final class ViewCreationTracker {
    private static final ViewCreationTracker INSTANCE = new ViewCreationTracker();
    private static final int MAX_STACK = 48;
    private final AtomicBoolean installed = new AtomicBoolean();
    private final Map<View, Record> records = new WeakHashMap<>();
    public static ViewCreationTracker get() { return INSTANCE; }
    private ViewCreationTracker() {}

    public void install() {
        if (!installed.compareAndSet(false, true)) return;
        try {
            for (Constructor<?> c : View.class.getDeclaredConstructors()) XposedBridge.hookMethod(c, capture("construct"));
            for (Method m : LayoutInflater.class.getDeclaredMethods()) if (m.getName().equals("inflate")) XposedBridge.hookMethod(m, captureResult("inflate"));
            for (Method m : ViewGroup.class.getDeclaredMethods()) if (m.getName().equals("addView")) XposedBridge.hookMethod(m, captureArgument("add", 0));
        } catch (Throwable error) { XposedBridge.log("ViewCreationTracker install failed: " + error); }
    }

    public Record get(View view) { synchronized (records) { return records.get(view); } }
    private XC_MethodHook capture(String phase) { return new XC_MethodHook(5) { @Override protected void afterHookedMethod(MethodHookParam p) { if (p.thisObject instanceof View v) remember(v, phase); } }; }
    private XC_MethodHook captureResult(String phase) { return new XC_MethodHook(5) { @Override protected void afterHookedMethod(MethodHookParam p) { if (p.getResult() instanceof View v) remember(v, phase); } }; }
    private XC_MethodHook captureArgument(String phase, int index) { return new XC_MethodHook(5) { @Override protected void afterHookedMethod(MethodHookParam p) { if (p.args.length > index && p.args[index] instanceof View v) remember(v, phase); } }; }
    private void remember(View view, String phase) {
        StackTraceElement[] raw = new Throwable().getStackTrace(); int n = Math.min(MAX_STACK, raw.length); StackTraceElement[] copy = new StackTraceElement[n]; System.arraycopy(raw,0,copy,0,n);
        synchronized (records) { Record r = records.get(view); if (r == null) { r = new Record(); records.put(view, r); } r.put(phase, copy); }
    }
    public static final class Record {
        private StackTraceElement[] construct, inflate, add;
        void put(String phase, StackTraceElement[] stack) { if ("construct".equals(phase) && construct == null) construct=stack; else if ("inflate".equals(phase)) inflate=stack; else if ("add".equals(phase)) add=stack; }
        public StackTraceElement[] construction() { return construct; }
        public StackTraceElement[] inflate() { return inflate; }
        public StackTraceElement[] add() { return add; }
    }
}
