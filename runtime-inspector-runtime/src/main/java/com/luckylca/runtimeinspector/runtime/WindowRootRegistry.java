package com.luckylca.runtimeinspector.runtime;

import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class WindowRootRegistry {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Set<View> ROOTS = Collections.newSetFromMap(new WeakHashMap<>());

    private WindowRootRegistry() {}

    static void install() throws Throwable {
        if (!INSTALLED.compareAndSet(false, true)) return;
        Class<?> global = Class.forName("android.view.WindowManagerGlobal");
        for (Method method : global.getDeclaredMethods()) {
            if (!"addView".equals(method.getName()) && !"removeView".equals(method.getName())
                    && !"removeViewImmediate".equals(method.getName())) continue;
            method.setAccessible(true);
            boolean add = "addView".equals(method.getName());
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof View view)) return;
                    synchronized (ROOTS) {
                        if (add) ROOTS.add(view); else ROOTS.remove(view);
                    }
                }
            });
        }
    }

    static List<View> snapshot() {
        synchronized (ROOTS) {
            return new ArrayList<>(ROOTS);
        }
    }
}
