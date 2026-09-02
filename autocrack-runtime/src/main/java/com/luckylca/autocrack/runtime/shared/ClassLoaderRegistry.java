package com.luckylca.autocrack.runtime.shared;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single observer for every ClassLoader-dependent capability. */
public final class ClassLoaderRegistry {
    public interface Listener { void onClassLoaded(Class<?> type); }
    private static final ClassLoaderRegistry INSTANCE = new ClassLoaderRegistry();
    private final AtomicBoolean installed = new AtomicBoolean();
    private final Set<ClassLoader> loaders = Collections.newSetFromMap(new WeakHashMap<>());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile ClassLoader fallback;

    public static ClassLoaderRegistry get() { return INSTANCE; }
    private ClassLoaderRegistry() {}

    public void install(ClassLoader initial) throws Throwable {
        register(initial);
        if (!installed.compareAndSet(false, true)) return;
        observe(ClassLoader.class.getDeclaredMethod("loadClass", String.class));
        observe(ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class));
        Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
        observe(base.getDeclaredMethod("findClass", String.class));
        for (Constructor<?> constructor : base.getDeclaredConstructors()) {
            XposedBridge.hookMethod(constructor, new XC_MethodHook(10) {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ClassLoader loader) register(loader);
                }
            });
        }
    }

    private void observe(Method method) {
        XposedBridge.hookMethod(method, new XC_MethodHook(10) {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || !(param.getResult() instanceof Class<?> type)) return;
                register(type.getClassLoader());
                for (Listener listener : listeners) {
                    try { listener.onClassLoaded(type); } catch (Throwable ignored) {}
                }
            }
        });
    }

    public void register(ClassLoader loader) {
        if (loader == null) loader = fallback;
        if (loader == null) return;
        if (fallback == null) fallback = loader;
        synchronized (loaders) { loaders.add(loader); }
    }

    public List<ClassLoader> snapshot() {
        synchronized (loaders) { return new ArrayList<>(loaders); }
    }

    public void addListener(Listener listener) { if (listener != null) listeners.add(listener); }
    public ClassLoader fallback() { return fallback; }
}
