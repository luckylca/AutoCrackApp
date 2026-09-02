package com.luckylca.autocrack.runtime.shared;

import android.view.View;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single WindowManagerGlobal observer shared by ui/runtime/control capabilities. */
public final class WindowRegistry {
    private static final WindowRegistry INSTANCE = new WindowRegistry();
    private final AtomicBoolean installed = new AtomicBoolean();
    private final Set<View> roots = Collections.newSetFromMap(new WeakHashMap<>());
    private final java.util.Map<View, Object> rootParams = new WeakHashMap<>();
    public static WindowRegistry get() { return INSTANCE; }
    private WindowRegistry() {}

    public void install() throws Throwable {
        if (!installed.compareAndSet(false, true)) return;
        Class<?> global = Class.forName("android.view.WindowManagerGlobal");
        for (Method method : global.getDeclaredMethods()) {
            String name = method.getName();
            if (!name.equals("addView") && !name.equals("removeView") && !name.equals("removeViewImmediate")) continue;
            method.setAccessible(true);
            boolean add = name.equals("addView");
            XposedBridge.hookMethod(method, new XC_MethodHook(20) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof View view)) return;
                    synchronized (roots) {
                        if (add) {
                            roots.add(view);
                            if (param.args.length > 1 && param.args[1] instanceof WindowManager.LayoutParams lp) rootParams.put(view, lp);
                        } else {
                            roots.remove(view);
                            rootParams.remove(view);
                        }
                    }
                }
            });
        }
    }

    public Object layoutParams(View root) {
        if (root == null) return null;
        synchronized (roots) {
            Object cached = rootParams.get(root);
            if (cached != null) return cached;
        }
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal");
            Method get = type.getDeclaredMethod("getInstance"); get.setAccessible(true);
            Object global = get.invoke(null);
            Field viewsField = type.getDeclaredField("mViews"); viewsField.setAccessible(true);
            Field paramsField = type.getDeclaredField("mParams"); paramsField.setAccessible(true);
            Object views = viewsField.get(global);
            Object params = paramsField.get(global);
            if (views instanceof List<?> viewList && params instanceof List<?> paramList) {
                for (int i = 0; i < viewList.size() && i < paramList.size(); i++) {
                    if (viewList.get(i) == root) return paramList.get(i);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public JSONObject describeLayoutParams(View root) {
        try {
            Object raw = layoutParams(root);
            if (!(raw instanceof WindowManager.LayoutParams lp)) return null;
            JSONObject value = new JSONObject()
                    .put("class", lp.getClass().getName())
                    .put("type", lp.type)
                    .put("type_name", windowTypeName(lp.type))
                    .put("flags", lp.flags)
                    .put("flags_hex", "0x" + Integer.toHexString(lp.flags))
                    .put("format", lp.format)
                    .put("gravity", lp.gravity)
                    .put("x", lp.x).put("y", lp.y)
                    .put("width", lp.width).put("height", lp.height)
                    .put("title", lp.getTitle() == null ? JSONObject.NULL : String.valueOf(lp.getTitle()));
            try { Field f = lp.getClass().getField("privateFlags"); value.put("private_flags", f.getInt(lp)); }
            catch (Throwable ignored) {}
            if (lp.token != null) value.put("token_hash", System.identityHashCode(lp.token));
            return value;
        } catch (Throwable error) {
            try { return new JSONObject().put("error", error.toString()); }
            catch (Exception impossible) { throw new AssertionError(impossible); }
        }
    }

    private static String windowTypeName(int type) {
        if (type >= WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW && type <= WindowManager.LayoutParams.LAST_APPLICATION_WINDOW) return "application";
        if (type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW && type <= WindowManager.LayoutParams.LAST_SUB_WINDOW) return "sub_window";
        if (type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW && type <= WindowManager.LayoutParams.LAST_SYSTEM_WINDOW) return "system";
        return "unknown";
    }

    public List<View> snapshot(int maxRoots) {
        LinkedHashSet<View> all = new LinkedHashSet<>();
        synchronized (roots) { all.addAll(roots); }
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal");
            Method get = type.getDeclaredMethod("getInstance"); get.setAccessible(true);
            Object global = get.invoke(null);
            Field field = type.getDeclaredField("mViews"); field.setAccessible(true);
            Object raw = field.get(global);
            if (raw instanceof List<?> list) for (Object item : list) if (item instanceof View view) all.add(view);
        } catch (Throwable ignored) {}
        List<View> out = new ArrayList<>(all);
        if (out.size() > maxRoots) return new ArrayList<>(out.subList(Math.max(0, out.size() - maxRoots), out.size()));
        return out;
    }
}
