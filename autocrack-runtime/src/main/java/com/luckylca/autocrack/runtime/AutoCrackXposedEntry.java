package com.luckylca.autocrack.runtime;

import android.app.Application;
import android.annotation.SuppressLint;
import android.content.Context;
import com.luckylca.autocrack.runtime.shared.ActivityRegistry;
import com.luckylca.autocrack.runtime.shared.ClassLoaderRegistry;
import com.luckylca.autocrack.runtime.shared.ObjectRegistry;
import com.luckylca.autocrack.runtime.shared.NativeBridge;
import com.luckylca.autocrack.runtime.shared.ViewCreationTracker;
import com.luckylca.autocrack.runtime.shared.WindowRegistry;
import com.luckylca.autocrack.runtime.shared.XmlBlockPeerRegistry;
import com.luckylca.runtimeinspector.runtime.InspectorEngine;
import com.luckylca.simplehook.runtime.RuntimeEngine;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The only Xposed bootstrap used by AutoCrack dynamic toolpacks. */
@SuppressLint("DiscouragedPrivateApi")
public final class AutoCrackXposedEntry implements IXposedHookLoadPackage {
    public static final String MODULE_PACKAGE = "com.luckylca.autocrack.runtime";
    private static final Set<String> STARTED = ConcurrentHashMap.newKeySet();
    private static final Set<String> INSTALLED_PROCESS_HOOKS = ConcurrentHashMap.newKeySet();

    private static void installXmlBlockPeerCapture(String packageName, String processName) {
        String key = packageName + ":" + processName + ":xmlblock-peer";
        if (!INSTALLED_PROCESS_HOOKS.add(key)) return;
        try {
            XposedHelpers.findAndHookMethod("android.content.res.XmlBlock", null, "newParser", int.class,
                    new XC_MethodHook(100) {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) return;
                            Object parser = param.getResult();
                            Object block = param.thisObject;
                            if (parser == null || block == null) return;
                            try {
                                long nativeTree = XposedHelpers.getLongField(block, "mNative");
                                long parseState = XposedHelpers.getLongField(parser, "mParseState");
                                int sourceResId = param.args != null && param.args.length > 0 && param.args[0] instanceof Integer
                                        ? (Integer) param.args[0] : 0;
                                XmlBlockPeerRegistry.get().record(parser, block, nativeTree, parseState, sourceResId);
                            } catch (Throwable error) {
                                XposedBridge.log("AutoCrack XmlBlock peer capture failed: " + error);
                            }
                        }
                    });
            XposedBridge.log("AutoCrack XmlBlock peer capture installed: " + key);
        } catch (Throwable error) {
            INSTALLED_PROCESS_HOOKS.remove(key);
            XposedBridge.log("AutoCrack XmlBlock peer hook unavailable: " + error);
        }
    }

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
                    NativeBridge.ensureLoaded(context);
                    ClassLoaderRegistry.get().install(loader);
                    ObjectRegistry.get().bindProcess(params.packageName, processName, android.os.Process.myPid());
                    ActivityRegistry.get().install((Application) param.thisObject);
                    WindowRegistry.get().install();
                    ViewCreationTracker.get().install();
                    installXmlBlockPeerCapture(params.packageName, processName);
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
