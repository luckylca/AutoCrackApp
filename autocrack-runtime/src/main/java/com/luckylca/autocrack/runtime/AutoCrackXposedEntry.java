package com.luckylca.autocrack.runtime;

import android.app.Application;
import android.annotation.SuppressLint;
import android.content.Context;
import com.luckylca.autocrack.runtime.shared.ActivityRegistry;
import com.luckylca.autocrack.runtime.shared.ClassLoaderRegistry;
import com.luckylca.autocrack.runtime.shared.ObjectRegistry;
import com.luckylca.autocrack.runtime.shared.ViewCreationTracker;
import com.luckylca.autocrack.runtime.shared.WindowRegistry;
import com.luckylca.runtimeinspector.runtime.InspectorEngine;
import com.luckylca.simplehook.runtime.RuntimeEngine;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The only Xposed bootstrap used by AutoCrack dynamic toolpacks. */
@SuppressLint("DiscouragedPrivateApi")
public final class AutoCrackXposedEntry implements IXposedHookLoadPackage {
    public static final String MODULE_PACKAGE = "com.luckylca.autocrack.runtime";
    private static final Set<String> STARTED = ConcurrentHashMap.newKeySet();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam params) throws Throwable {
        if (MODULE_PACKAGE.equals(params.packageName)) return;
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        XposedBridge.hookMethod(attach, new XC_MethodHook(100) {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                String processName = Application.getProcessName();
                String key = params.packageName + ":" + processName + ":" + android.os.Process.myPid();
                if (!STARTED.add(key)) return;
                try {
                    ClassLoader loader = params.classLoader != null ? params.classLoader : context.getClassLoader();
                    ClassLoaderRegistry.get().install(loader);
                    ObjectRegistry.get().bindProcess(params.packageName, processName, android.os.Process.myPid());
                    ActivityRegistry.get().install((Application) param.thisObject);
                    WindowRegistry.get().install();
                    ViewCreationTracker.get().install();
                    new RuntimeEngine(context, params.packageName, processName, loader).start();
                    new InspectorEngine(context, params.packageName, processName).start();
                    XposedBridge.log("AutoCrack Runtime attached: " + key);
                } catch (Throwable error) {
                    XposedBridge.log("AutoCrack Runtime startup failed: " + error);
                }
            }
        });
    }
}
