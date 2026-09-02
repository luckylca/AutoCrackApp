package com.luckylca.autocrack.runtime.shared;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import org.json.JSONObject;

/** JNI helpers for native Layout-Inspect-style strategies. */
public final class NativeBridge {
    private static volatile boolean attempted;
    private static volatile boolean loaded;
    private static volatile String loadError;
    private NativeBridge() {}

    public static synchronized boolean ensureLoaded(Context context) {
        if (attempted) return loaded;
        attempted = true;
        try {
            System.loadLibrary("autocrack_runtime_native");
            loaded = true;
            return true;
        } catch (Throwable first) {
            loadError = first.toString();
            try {
                Context moduleContext = context.createPackageContext("com.luckylca.autocrack.runtime", Context.CONTEXT_IGNORE_SECURITY);
                ApplicationInfo info = moduleContext.getApplicationInfo();
                String path = info.nativeLibraryDir + "/libautocrack_runtime_native.so";
                System.load(path);
                loaded = true;
                loadError = null;
                return true;
            } catch (Throwable second) {
                loadError = first + " ; absolute fallback failed: " + second;
                loaded = false;
                return false;
            }
        }
    }

    public static boolean isLoaded() { return loaded; }
    public static String loadError() { return loadError; }

    public static byte[] readMemory(Context context, long address, int size) throws Exception {
        if (!ensureLoaded(context)) throw new IllegalStateException("Native bridge unavailable: " + loadError);
        return nativeReadMemory(address, size);
    }

    public static JSONObject dlopen(Context context, String path, int flags) throws Exception {
        if (!ensureLoaded(context)) return new JSONObject().put("ok", false).put("loaded", false).put("reason", loadError);
        String raw = nativeDlopen(path, flags);
        if (raw != null && raw.startsWith("OK:")) return new JSONObject().put("ok", true).put("loaded", true).put("handle", raw.substring(3));
        return new JSONObject().put("ok", false).put("loaded", false).put("reason", raw == null ? "native returned null" : raw.substring(Math.min(4, raw.length())));
    }


    public static JSONObject androidDlopenExt(Context context, String path, int flags, int extFlags) throws Exception {
        if (!ensureLoaded(context)) return new JSONObject().put("ok", false).put("loaded", false).put("reason", loadError);
        String raw = nativeAndroidDlopenExt(path, flags, extFlags);
        if (raw != null && raw.startsWith("OK:")) return new JSONObject().put("ok", true).put("loaded", true).put("handle", raw.substring(3));
        return new JSONObject().put("ok", false).put("loaded", false).put("reason", raw == null ? "native returned null" : raw.substring(Math.min(4, raw.length())));
    }

    public static JSONObject dladdr(Context context, long address) throws Exception {
        if (!ensureLoaded(context)) return new JSONObject().put("ok", false).put("supported", false).put("reason", loadError);
        String raw = nativeDladdr(address);
        if (raw == null || !raw.startsWith("OK:")) return new JSONObject().put("ok", false).put("reason", raw == null ? "native returned null" : raw);
        String[] parts = raw.substring(3).split("\\|", -1);
        return new JSONObject().put("ok", true)
                .put("file", parts.length > 0 && !parts[0].isEmpty() ? parts[0] : JSONObject.NULL)
                .put("base", parts.length > 1 ? parts[1] : JSONObject.NULL)
                .put("symbol", parts.length > 2 && !parts[2].isEmpty() ? parts[2] : JSONObject.NULL)
                .put("symbol_address", parts.length > 3 ? parts[3] : JSONObject.NULL);
    }

    public static JSONObject dlsym(Context context, String handle, String symbol) throws Exception {
        if (!ensureLoaded(context)) return new JSONObject().put("ok", false).put("resolved", false).put("reason", loadError);
        String raw = nativeDlsym(handle == null ? "" : handle, symbol == null ? "" : symbol);
        if (raw != null && raw.startsWith("OK:")) {
            return new JSONObject().put("ok", true).put("resolved", true)
                    .put("handle", handle == null || handle.isBlank() ? "RTLD_DEFAULT" : handle)
                    .put("symbol", symbol)
                    .put("address", raw.substring(3));
        }
        return new JSONObject().put("ok", false).put("resolved", false)
                .put("handle", handle == null || handle.isBlank() ? "RTLD_DEFAULT" : handle)
                .put("symbol", symbol)
                .put("reason", raw == null ? "native returned null" : raw.substring(Math.min(4, raw.length())));
    }

    public static JSONObject probe(Context context) throws Exception {
        if (!ensureLoaded(context)) return new JSONObject().put("ok", false).put("supported", false).put("reason", loadError);
        return new JSONObject(nativeProbe());
    }

    public static JSONObject modules(Context context, int maxModules, String filter) throws Exception {
        if (!ensureLoaded(context)) return new JSONObject().put("ok", false).put("supported", false).put("reason", loadError);
        int boundedMax = Math.max(1, Math.min(maxModules, 4096));
        return new JSONObject(nativeModules(boundedMax, filter == null ? "" : filter));
    }

    private static native byte[] nativeReadMemory(long address, int size) throws Exception;
    private static native String nativeDlopen(String path, int flags);
    private static native String nativeAndroidDlopenExt(String path, int flags, int extFlags);
    private static native String nativeDladdr(long address);
    private static native String nativeDlsym(String handle, String symbol);
    private static native String nativeModules(int maxModules, String filter);
    private static native String nativeProbe();
}
