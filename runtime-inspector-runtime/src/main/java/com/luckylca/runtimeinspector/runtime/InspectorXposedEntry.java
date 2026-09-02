package com.luckylca.runtimeinspector.runtime;

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

@SuppressLint("DiscouragedPrivateApi")
public final class InspectorXposedEntry implements IXposedHookLoadPackage {
    private static final String MODULE_PACKAGE = "com.luckylca.runtimeinspector.runtime";
    private static final Set<String> STARTED = ConcurrentHashMap.newKeySet();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        if (MODULE_PACKAGE.equals(param.packageName)) return;
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        XposedBridge.hookMethod(attach, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam hook) {
                String key = param.processName + ":" + android.os.Process.myPid();
                if (!STARTED.add(key)) return;
                try {
                    Context context = (Context) hook.args[0];
                    new InspectorEngine(context, param.packageName, param.processName).start();
                } catch (Throwable error) {
                    XposedBridge.log("RuntimeInspector startup failed: " + error);
                    XposedBridge.log(error);
                }
            }
        });
    }
}
