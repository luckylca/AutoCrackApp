package com.luckylca.simplehook.runtime;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@SuppressLint("DiscouragedPrivateApi") // Application.attach is the stable legacy Xposed context entry point.
public final class SimpleHookXposedEntry implements IXposedHookLoadPackage {
    private static final Set<String> STARTED = ConcurrentHashMap.newKeySet();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if ("com.luckylca.simplehook.runtime".equals(loadPackageParam.packageName)) return;
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        XposedBridge.hookMethod(attach, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String key = loadPackageParam.processName + ":" + android.os.Process.myPid();
                if (!STARTED.add(key)) return;
                try {
                    Context context = (Context) param.args[0];
                    new RuntimeEngine(
                            context,
                            loadPackageParam.packageName,
                            loadPackageParam.processName,
                            loadPackageParam.classLoader).start();
                } catch (Throwable error) {
                    XposedBridge.log("SimpleHook startup failed: " + error);
                    XposedBridge.log(error);
                }
            }
        });
    }
}
