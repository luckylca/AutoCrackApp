package com.luckylca.autocrack.runtime.shared;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.VideoView;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** WebView and mutating runtime-control capabilities. UI mutations execute on the main looper. */
public final class WebControlIntrospector {
    private static final int MAX_VIEWS = 4_000;
    private static final int MAX_WEBVIEWS = 128;
    private static final int MAX_JS = 64 * 1024;
    private static final ConcurrentHashMap<String, EvalResult> EVALS = new ConcurrentHashMap<>();
    private WebControlIntrospector() {}

    public static boolean supports(String kind) {
        return Set.of(
                "webview.list", "webview.info", "webview.debug", "webview.eval", "webview.eval.result", "webview.load_url", "webview.reload", "webview.go_back", "webview.go_forward", "webview.clear_cache",
                "control.secure.status", "control.secure.diagnose", "control.secure.disable", "control.so.inject", "control.so.dlopen", "control.so.android_dlopen_ext", "control.so.dlsym",
                "control.activity.start", "control.process.kill", "control.object.field.set", "control.object.method.call").contains(kind);
    }

    public static JSONObject execute(Context context, JSONObject request) throws Exception {
        return switch (request.getString("kind")) {
            case "webview.list" -> webviews();
            case "webview.info" -> webviewInfo(request);
            case "webview.debug" -> webviewDebug(request);
            case "webview.eval" -> webviewEval(request);
            case "webview.eval.result" -> webviewEvalResult(request);
            case "webview.load_url" -> webviewLoadUrl(request);
            case "webview.reload" -> webviewReload(request);
            case "webview.go_back" -> webviewGo(request, true);
            case "webview.go_forward" -> webviewGo(request, false);
            case "webview.clear_cache" -> webviewClearCache(request);
            case "control.secure.status" -> secureStatus();
            case "control.secure.diagnose" -> secureStatus().put("diagnose", true);
            case "control.secure.disable" -> secureDisable();
            case "control.so.inject" -> injectSo(request);
            case "control.so.dlopen" -> dlopenSo(context, request);
            case "control.so.android_dlopen_ext" -> androidDlopenExtSo(context, request);
            case "control.so.dlsym" -> dlsymSo(context, request);
            case "control.activity.start" -> startActivity(context, request);
            case "control.process.kill" -> killProcess(request);
            case "control.object.field.set" -> objectFieldSet(request);
            case "control.object.method.call" -> objectMethodCall(request);
            default -> error("UNSUPPORTED_KIND", request.optString("kind"));
        };
    }

    private static JSONObject webviews() throws Exception {
        JSONArray values = new JSONArray();
        for (WebView web : findWebViews()) values.put(webInfo(web));
        return ok().put("count", values.length()).put("webviews", values)
                .put("devtools_note", "WebView.setWebContentsDebuggingEnabled enables chrome_devtools_remote discovery; socket forwarding is handled by the host bridge.");
    }

    private static JSONObject webviewInfo(JSONObject request) throws Exception {
        WebView web = requireWebView(request.optString("handle", ""));
        if (web == null) return error("WEBVIEW_NOT_FOUND", request.optString("handle", ""));
        return ok().put("webview", webInfo(web));
    }

    private static JSONObject webInfo(WebView web) throws Exception {
        JSONObject value = new JSONObject().put("handle", ObjectRegistry.get().put(web, false, "webview"))
                .put("class", web.getClass().getName())
                .put("url", nullable(safe(() -> web.getUrl())))
                .put("original_url", nullable(safe(() -> web.getOriginalUrl())))
                .put("title", nullable(safe(() -> web.getTitle())))
                .put("progress", web.getProgress())
                .put("can_go_back", web.canGoBack())
                .put("can_go_forward", web.canGoForward());
        try {
            WebSettings settings = web.getSettings();
            value.put("user_agent", settings.getUserAgentString())
                    .put("javascript_enabled", settings.getJavaScriptEnabled())
                    .put("dom_storage_enabled", settings.getDomStorageEnabled());
        } catch (Throwable error) { value.put("settings_error", error.toString()); }
        try {
            String url = web.getUrl();
            value.put("cookie", url == null ? JSONObject.NULL : nullable(CookieManager.getInstance().getCookie(url)));
        } catch (Throwable error) { value.put("cookie_error", error.toString()); }
        value.put("javascript_interfaces", discoverJavascriptInterfaces(web));
        return value;
    }

    private static JSONObject webviewDebug(JSONObject request) throws Exception {
        boolean enabled = request.optBoolean("enabled", true);
        WebView.setWebContentsDebuggingEnabled(enabled);
        return ok().put("enabled", enabled).put("strategy", "WebView.setWebContentsDebuggingEnabled")
                .put("next", "Use android-shell/adb to enumerate and forward chrome_devtools_remote sockets when DOM/Network CDP access is needed.");
    }

    private static JSONObject webviewEval(JSONObject request) throws Exception {
        WebView web = requireWebView(request.optString("handle", ""));
        if (web == null) return error("WEBVIEW_NOT_FOUND", request.optString("handle", ""));
        String script = request.optString("script", "");
        if (script.length() > MAX_JS) return error("SCRIPT_TOO_LARGE", "JavaScript exceeds " + MAX_JS + " characters");
        String token = "js_" + UUID.randomUUID().toString().replace("-", "");
        EVALS.put(token, new EvalResult(false, null, null, System.currentTimeMillis()));
        web.evaluateJavascript(script, result -> EVALS.put(token,
                new EvalResult(true, result, null, System.currentTimeMillis())));
        pruneEvals();
        return ok().put("token", token).put("pending", true)
                .put("note", "Evaluation callback is asynchronous; poll webview.eval.result with this token.");
    }

    private static JSONObject webviewEvalResult(JSONObject request) throws Exception {
        String token = request.getString("token"); EvalResult value = EVALS.get(token);
        if (value == null) return error("EVAL_NOT_FOUND", token);
        if (!value.done) return ok().put("token", token).put("pending", true);
        EVALS.remove(token);
        JSONObject out = ok().put("token", token).put("pending", false);
        if (value.error != null) return out.put("error_text", value.error);
        return out.put("value", value.value == null ? JSONObject.NULL : value.value);
    }


    private static JSONObject webviewLoadUrl(JSONObject request) throws Exception {
        WebView web = requireWebView(request.optString("handle", ""));
        if (web == null) return error("WEBVIEW_NOT_FOUND", request.optString("handle", ""));
        String url = request.optString("url", "");
        if (url.isBlank()) return error("URL_REQUIRED", "url is required");
        web.loadUrl(url);
        return ok().put("handle", ObjectRegistry.get().put(web, false, "webview"))
                .put("loaded", true).put("url", url).put("persistent", false)
                .put("strategy", "WebView.loadUrl");
    }

    private static JSONObject webviewReload(JSONObject request) throws Exception {
        WebView web = requireWebView(request.optString("handle", ""));
        if (web == null) return error("WEBVIEW_NOT_FOUND", request.optString("handle", ""));
        web.reload();
        return ok().put("handle", ObjectRegistry.get().put(web, false, "webview"))
                .put("reloaded", true).put("url", nullable(safe(() -> web.getUrl())))
                .put("persistent", false).put("strategy", "WebView.reload");
    }

    private static JSONObject webviewGo(JSONObject request, boolean back) throws Exception {
        WebView web = requireWebView(request.optString("handle", ""));
        if (web == null) return error("WEBVIEW_NOT_FOUND", request.optString("handle", ""));
        boolean can = back ? web.canGoBack() : web.canGoForward();
        if (!can) return error(back ? "CANNOT_GO_BACK" : "CANNOT_GO_FORWARD", "WebView has no matching history entry");
        if (back) web.goBack(); else web.goForward();
        return ok().put("handle", ObjectRegistry.get().put(web, false, "webview"))
                .put("direction", back ? "back" : "forward").put("moved", true)
                .put("persistent", false).put("strategy", back ? "WebView.goBack" : "WebView.goForward");
    }

    private static JSONObject webviewClearCache(JSONObject request) throws Exception {
        WebView web = requireWebView(request.optString("handle", ""));
        if (web == null) return error("WEBVIEW_NOT_FOUND", request.optString("handle", ""));
        boolean includeDisk = request.optBoolean("include_disk_files", false);
        web.clearCache(includeDisk);
        return ok().put("handle", ObjectRegistry.get().put(web, false, "webview"))
                .put("cleared", true).put("include_disk_files", includeDisk)
                .put("persistent", false).put("strategy", "WebView.clearCache");
    }

    private static JSONObject secureStatus() throws Exception {
        JSONArray windows = new JSONArray(); int secure = 0;
        for (ActivityRegistry.ActivitySnapshot snapshot : ActivityRegistry.get().snapshot()) {
            Activity activity = snapshot.activity(); Window window = activity.getWindow(); if (window == null) continue;
            JSONObject item = secureWindow(activity, window);
            if (item.optBoolean("flag_secure", false)) secure++;
            windows.put(item);
        }
        return ok().put("secure_window_count", secure).put("window_count", windows.length()).put("windows", windows)
                .put("surface_summary", secureSurfaceSummary())
                .put("view_surface_cause_supported", false)
                .put("scope_note", "Stable strategy identifies and clears Window.FLAG_SECURE. Vendor/private secure SurfaceControl or DRM producer surfaces are diagnosed as possible causes but remain outside this Java capability.");
    }

    private static JSONObject secureDisable() throws Exception {
        JSONArray changed = new JSONArray();
        for (ActivityRegistry.ActivitySnapshot snapshot : ActivityRegistry.get().snapshot()) {
            Activity activity = snapshot.activity(); Window window = activity.getWindow(); if (window == null) continue;
            int before = window.getAttributes().flags;
            if ((before & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                int after = window.getAttributes().flags;
                changed.put(new JSONObject().put("activity", activity.getClass().getName())
                        .put("activity_handle", ObjectRegistry.get().put(activity, false, "activity"))
                        .put("window_handle", ObjectRegistry.get().put(window, false, "window"))
                        .put("flags_before", before).put("flags_after", after)
                        .put("flag_secure_before", true)
                        .put("flag_secure_after", (after & WindowManager.LayoutParams.FLAG_SECURE) != 0));
            }
        }
        return ok().put("changed", changed.length()).put("windows", changed)
                .put("after_status", secureStatus())
                .put("persistent", false).put("strategy", "Window.clearFlags(FLAG_SECURE)")
                .put("scope_note", "This clears Window.FLAG_SECURE only; private SurfaceControl, DRM, or vendor-secure producers may still block screenshots.");
    }

    private static JSONObject secureWindow(Activity activity, Window window) throws Exception {
        int flags = window.getAttributes().flags;
        View decor = window.getDecorView();
        return new JSONObject().put("activity", activity.getClass().getName())
                .put("activity_handle", ObjectRegistry.get().put(activity, false, "activity"))
                .put("window_handle", ObjectRegistry.get().put(window, false, "window"))
                .put("decor_handle", decor == null ? JSONObject.NULL : ObjectRegistry.get().put(decor, false, "ui"))
                .put("decor_class", decor == null ? JSONObject.NULL : decor.getClass().getName())
                .put("decor_attached", decor != null && decor.isAttachedToWindow())
                .put("decor_shown", decor != null && decor.isShown())
                .put("flags", flags)
                .put("flag_secure", (flags & WindowManager.LayoutParams.FLAG_SECURE) != 0)
                .put("window_type", window.getAttributes().type)
                .put("soft_input_mode", window.getAttributes().softInputMode)
                .put("surface_counts", decor == null ? new JSONObject() : secureSurfaceCounts(decor));
    }

    private static JSONObject secureSurfaceSummary() throws Exception {
        JSONArray roots = new JSONArray();
        JSONObject total = new JSONObject().put("surface_view_count", 0).put("texture_view_count", 0).put("video_view_count", 0).put("root_count", 0);
        for (View root : WindowRegistry.get().snapshot(64)) {
            JSONObject counts = secureSurfaceCounts(root);
            roots.put(new JSONObject().put("root_handle", ObjectRegistry.get().put(root, false, "ui"))
                    .put("root_class", root.getClass().getName()).put("counts", counts));
            total.put("root_count", total.getInt("root_count") + 1);
            total.put("surface_view_count", total.getInt("surface_view_count") + counts.optInt("surface_view_count", 0));
            total.put("texture_view_count", total.getInt("texture_view_count") + counts.optInt("texture_view_count", 0));
            total.put("video_view_count", total.getInt("video_view_count") + counts.optInt("video_view_count", 0));
        }
        return new JSONObject().put("total", total).put("roots", roots)
                .put("interpretation", "SurfaceView/TextureView/VideoView presence can explain screenshots that remain blank after Window.FLAG_SECURE is cleared.");
    }

    private static JSONObject secureSurfaceCounts(View root) throws Exception {
        int[] counts = new int[4];
        Set<View> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        countSecureSurfaces(root, seen, counts);
        return new JSONObject().put("visited", counts[0]).put("surface_view_count", counts[1])
                .put("texture_view_count", counts[2]).put("video_view_count", counts[3]);
    }

    private static void countSecureSurfaces(View view, Set<View> seen, int[] counts) {
        if (view == null || !seen.add(view) || counts[0] >= MAX_VIEWS) return;
        counts[0]++;
        if (view instanceof SurfaceView) counts[1]++;
        if (view instanceof TextureView) counts[2]++;
        if (view instanceof VideoView) counts[3]++;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) countSecureSurfaces(group.getChildAt(i), seen, counts);
    }

    private static JSONObject injectSo(JSONObject request) throws Exception {
        String path = request.optString("path", "");
        if (path.isBlank() || !path.startsWith("/")) return error("ABSOLUTE_PATH_REQUIRED", "An absolute target-process-visible .so path is required");
        try {
            System.load(path);
            return ok().put("path", path).put("loaded", true).put("strategy", "System.load")
                    .put("namespace_note", "Android linker namespace policy still applies; failures are returned verbatim rather than hidden.");
        } catch (Throwable error) {
            return ok().put("supported", false).put("path", path).put("loaded", false)
                    .put("reason", error.toString())
                    .put("strategies", new JSONArray().put("System.load absolute path")
                            .put("root copy into target-visible namespace before retry")
                            .put("native dlopen/linker namespace strategy not embedded"));
        }
    }

    private static JSONObject dlopenSo(Context context, JSONObject request) throws Exception {
        String path = request.optString("path", "");
        if (path.isBlank() || !path.startsWith("/")) return error("ABSOLUTE_PATH_REQUIRED", "An absolute target-process-visible .so path is required");
        int flags = request.optInt("flags", 2); // RTLD_NOW by default.
        JSONObject result = NativeBridge.dlopen(context, path, flags);
        if (result.optBoolean("ok")) return ok().put("path", path).put("loaded", true)
                .put("handle", result.optString("handle", "")).put("strategy", "native dlopen");
        return ok().put("supported", false).put("path", path).put("loaded", false)
                .put("reason", result.optString("reason", "native dlopen failed"))
                .put("strategies", new JSONArray().put("native dlopen absolute path").put("System.load fallback via control.so.inject"));
    }

    private static JSONObject androidDlopenExtSo(Context context, JSONObject request) throws Exception {
        String path = request.optString("path", "");
        if (path.isBlank() || !path.startsWith("/")) return error("ABSOLUTE_PATH_REQUIRED", "An absolute target-process-visible .so path is required");
        int flags = request.optInt("flags", 2); // RTLD_NOW by default.
        int extFlags = request.optInt("ext_flags", 0);
        JSONObject result = NativeBridge.androidDlopenExt(context, path, flags, extFlags);
        if (result.optBoolean("ok")) return ok().put("path", path).put("loaded", true)
                .put("handle", result.optString("handle", "")).put("flags", flags).put("ext_flags", extFlags)
                .put("strategy", "native android_dlopen_ext")
                .put("namespace_bypass", false)
                .put("namespace_note", "ANDROID_DLEXT_USE_NAMESPACE requires an android_namespace_t pointer; namespace bypass is intentionally not claimed.");
        return ok().put("supported", false).put("path", path).put("loaded", false).put("flags", flags).put("ext_flags", extFlags)
                .put("reason", result.optString("reason", "native android_dlopen_ext failed"))
                .put("strategies", new JSONArray().put("android_dlopen_ext absolute path").put("ANDROID_DLEXT_USE_LIBRARY_FD when requested").put("control.so.dlopen fallback"));
    }

    private static JSONObject dlsymSo(Context context, JSONObject request) throws Exception {
        String symbol = request.optString("symbol", "");
        if (symbol.isBlank()) return error("SYMBOL_REQUIRED", "symbol is required");
        String handle = request.optString("handle", "");
        JSONObject result = NativeBridge.dlsym(context, handle, symbol);
        if (result.optBoolean("ok", false)) {
            return result.put("strategy", "native dlsym").put("callable", false)
                    .put("note", "This resolves a symbol address only; it does not call the function.");
        }
        return ok().put("supported", false).put("capability", "control.so.dlsym")
                .put("reason", result.optString("reason", "native dlsym failed"))
                .put("strategies", new JSONArray().put("RTLD_DEFAULT/global lookup").put("explicit dlopen handle lookup"));
    }

    private static JSONObject startActivity(Context context, JSONObject request) throws Exception {
        Intent intent = new Intent();
        String component = request.optString("component", "");
        String className = request.optString("class", "");
        if (!component.isBlank()) {
            ComponentName name = ComponentName.unflattenFromString(component);
            if (name == null) return error("INVALID_COMPONENT", component);
            intent.setComponent(name);
        } else if (!className.isBlank()) {
            intent.setClassName(request.optString("package", context.getPackageName()), className);
        }
        if (request.has("action")) intent.setAction(request.optString("action", null));
        if (request.has("data")) intent.setData(Uri.parse(request.getString("data")));
        intent.addFlags(request.optInt("flags", Intent.FLAG_ACTIVITY_NEW_TASK));
        context.startActivity(intent);
        return ok().put("started", true).put("component", intent.getComponent() == null ? JSONObject.NULL : intent.getComponent().flattenToShortString())
                .put("strategy", "target Runtime Context.startActivity");
    }

    private static JSONObject killProcess(JSONObject request) throws Exception {
        int delay = Math.max(100, Math.min(request.optInt("delay_ms", 350), 5_000));
        int pid = Process.myPid();
        new Handler(Looper.getMainLooper()).postDelayed(() -> Process.killProcess(pid), delay);
        return ok().put("pid", pid).put("scheduled", true).put("delay_ms", delay)
                .put("note", "Delayed so the request result can be committed before process termination.");
    }


    private static JSONObject objectFieldSet(JSONObject request) throws Exception {
        String handle = request.optString("handle", "");
        Object target = ObjectRegistry.get().get(handle);
        if (target == null) return error("STALE_HANDLE", handle);
        String name = request.optString("field", "");
        if (name.isBlank()) return error("FIELD_REQUIRED", "field is required");
        String declaring = request.optString("declaring_class", "");
        Field field = findFieldForWrite(target.getClass(), name, declaring);
        if (field == null) return error("FIELD_NOT_FOUND", (declaring.isBlank() ? target.getClass().getName() : declaring) + "#" + name);
        field.setAccessible(true);
        Object receiver = Modifier.isStatic(field.getModifiers()) ? null : target;
        Object before = null;
        try { before = field.get(receiver); } catch (Throwable ignored) {}
        Object raw = request.has("value") ? request.get("value") : JSONObject.NULL;
        Object coerced = coerceJsonValue(raw, field.getType());
        field.set(receiver, coerced);
        Object after = null;
        try { after = field.get(receiver); } catch (Throwable ignored) {}
        return ok().put("handle", handle)
                .put("class", target.getClass().getName())
                .put("declaring_class", field.getDeclaringClass().getName())
                .put("field", field.getName())
                .put("type", field.getType().getName())
                .put("static", Modifier.isStatic(field.getModifiers()))
                .put("value_before", summarizeValue(before))
                .put("value_after", summarizeValue(after))
                .put("persistent", false)
                .put("strategy", "bounded reflection field write in target runtime");
    }

    private static Field findFieldForWrite(Class<?> type, String name, String declaring) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            if (!declaring.isBlank() && !cursor.getName().equals(declaring)) continue;
            try { return cursor.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static Object coerceJsonValue(Object raw, Class<?> type) throws Exception {
        if (raw == null || raw == JSONObject.NULL) {
            if (type.isPrimitive()) throw new IllegalArgumentException("Cannot assign null to primitive " + type.getName());
            return null;
        }
        if (type == String.class || CharSequence.class.isAssignableFrom(type)) return String.valueOf(raw);
        if (type == boolean.class || type == Boolean.class) {
            if (raw instanceof Boolean b) return b;
            return Boolean.parseBoolean(String.valueOf(raw));
        }
        if (type == byte.class || type == Byte.class) return ((Number) number(raw, type)).byteValue();
        if (type == short.class || type == Short.class) return ((Number) number(raw, type)).shortValue();
        if (type == int.class || type == Integer.class) return ((Number) number(raw, type)).intValue();
        if (type == long.class || type == Long.class) return ((Number) number(raw, type)).longValue();
        if (type == float.class || type == Float.class) return ((Number) number(raw, type)).floatValue();
        if (type == double.class || type == Double.class) return ((Number) number(raw, type)).doubleValue();
        if (type == char.class || type == Character.class) {
            String text = String.valueOf(raw);
            if (text.length() != 1) throw new IllegalArgumentException("char field requires a one-character string");
            return text.charAt(0);
        }
        if (type.isEnum()) {
            String wanted = String.valueOf(raw);
            Object[] constants = type.getEnumConstants();
            if (constants != null) for (Object item : constants) if (((Enum<?>) item).name().equals(wanted)) return item;
            throw new IllegalArgumentException("Unknown enum constant " + wanted + " for " + type.getName());
        }
        throw new IllegalArgumentException("Unsupported field write type " + type.getName() + "; use SimpleHook or a typed mutator for complex object assignment");
    }

    private static Number number(Object raw, Class<?> targetType) {
        if (raw instanceof Number n) return n;
        String text = String.valueOf(raw).trim();
        try {
            if (targetType == float.class || targetType == Float.class || targetType == double.class || targetType == Double.class) return Double.parseDouble(text);
            if (text.startsWith("0x") || text.startsWith("0X")) return Long.parseLong(text.substring(2), 16);
            return Long.parseLong(text);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid numeric value for " + targetType.getName() + ": " + raw);
        }
    }

    private static Object summarizeValue(Object value) throws Exception {
        if (value == null) return JSONObject.NULL;
        Class<?> type = value.getClass();
        if (type.isPrimitive() || value instanceof Number || value instanceof Boolean || value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>) return cut(String.valueOf(value));
        return new JSONObject().put("class", type.getName()).put("text", cut(String.valueOf(value))).put("handle", ObjectRegistry.get().put(value, false, "object"));
    }


    private static JSONObject objectMethodCall(JSONObject request) throws Exception {
        String handle = request.optString("handle", "");
        Object target = ObjectRegistry.get().get(handle);
        if (target == null) return error("STALE_HANDLE", handle);
        String name = request.optString("method", "");
        if (name.isBlank()) return error("METHOD_REQUIRED", "method is required");
        String declaring = request.optString("declaring_class", "");
        JSONArray argValues = request.optJSONArray("args");
        JSONArray argTypes = request.optJSONArray("arg_types");
        if (argValues == null) argValues = new JSONArray();
        if (argTypes == null) argTypes = new JSONArray();
        if (argValues.length() != argTypes.length()) return error("ARG_MISMATCH", "args and arg_types must have the same length");
        if (argValues.length() > 16) return error("TOO_MANY_ARGS", "method call supports at most 16 arguments");
        Class<?>[] parameterTypes = new Class<?>[argTypes.length()];
        Object[] values = new Object[argValues.length()];
        ClassLoader loader = target.getClass().getClassLoader();
        for (int i = 0; i < argTypes.length(); i++) {
            parameterTypes[i] = resolveType(argTypes.getString(i), loader);
            values[i] = coerceJsonValue(argValues.get(i), parameterTypes[i]);
        }
        Method method = findMethodForCall(target.getClass(), name, declaring, parameterTypes);
        if (method == null) return error("METHOD_NOT_FOUND", (declaring.isBlank() ? target.getClass().getName() : declaring) + "#" + name);
        method.setAccessible(true);
        Object receiver = Modifier.isStatic(method.getModifiers()) ? null : target;
        Object result = method.invoke(receiver, values);
        return ok().put("handle", handle)
                .put("class", target.getClass().getName())
                .put("declaring_class", method.getDeclaringClass().getName())
                .put("method", method.getName())
                .put("return_type", method.getReturnType().getName())
                .put("static", Modifier.isStatic(method.getModifiers()))
                .put("result", method.getReturnType() == Void.TYPE ? JSONObject.NULL : summarizeValue(result))
                .put("persistent", false)
                .put("strategy", "bounded reflection method call in target runtime");
    }

    private static Method findMethodForCall(Class<?> type, String name, String declaring, Class<?>[] parameters) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            if (!declaring.isBlank() && !cursor.getName().equals(declaring)) continue;
            try { return cursor.getDeclaredMethod(name, parameters); } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private static Class<?> resolveType(String name, ClassLoader loader) throws ClassNotFoundException {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "char" -> char.class;
            case "void" -> void.class;
            default -> Class.forName(name, false, loader == null ? WebControlIntrospector.class.getClassLoader() : loader);
        };
    }

    private static WebView requireWebView(String handle) {
        if (!handle.isBlank()) { Object value = ObjectRegistry.get().get(handle); return value instanceof WebView web ? web : null; }
        List<WebView> all = findWebViews(); return all.isEmpty() ? null : all.get(0);
    }

    private static List<WebView> findWebViews() {
        List<WebView> out = new ArrayList<>(); Set<View> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int[] budget = {MAX_VIEWS};
        for (View root : WindowRegistry.get().snapshot(64)) collect(root, out, seen, budget);
        return out.size() > MAX_WEBVIEWS ? new ArrayList<>(out.subList(0, MAX_WEBVIEWS)) : out;
    }
    private static void collect(View view, List<WebView> out, Set<View> seen, int[] budget) {
        if (view == null || budget[0]-- <= 0 || !seen.add(view) || out.size() >= MAX_WEBVIEWS) return;
        if (view instanceof WebView web) out.add(web);
        if (view instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) collect(group.getChildAt(i), out, seen, budget);
    }

    private static JSONArray discoverJavascriptInterfaces(WebView web) {
        JSONArray out = new JSONArray(); LinkedHashMap<String,Object> found = new LinkedHashMap<>(); Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        inspectInterfaceFields(web, "webview", 0, 3, found, seen);
        for (Map.Entry<String,Object> entry : found.entrySet()) {
            try { out.put(new JSONObject().put("path", entry.getKey()).put("class", entry.getValue().getClass().getName())
                    .put("handle", ObjectRegistry.get().put(entry.getValue(), false, "webview"))); } catch (Throwable ignored) {}
        }
        return out;
    }
    private static void inspectInterfaceFields(Object object, String path, int depth, int maxDepth, Map<String,Object> out, Set<Object> seen) {
        if (object == null || depth > maxDepth || !seen.add(object) || out.size() >= 64) return;
        for (Class<?> type=object.getClass(); type!=null && type.getName().startsWith("android") || type!=null && type.getName().contains("webview"); type=type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                String lower = field.getName().toLowerCase();
                try {
                    field.setAccessible(true); Object value=field.get(object); if(value==null)continue;
                    String next=path+"."+field.getName();
                    if (lower.contains("javascript") && lower.contains("interface")) {
                        if(value instanceof Map<?,?> map) for(Object item:map.values()) if(item!=null) out.put(next,item); else {}
                        else out.put(next,value);
                    }
                    if(depth<maxDepth && (lower.contains("provider")||lower.contains("contents")||lower.contains("webview")||lower.contains("javascript"))) inspectInterfaceFields(value,next,depth+1,maxDepth,out,seen);
                } catch(Throwable ignored){}
            }
            if(type.getSuperclass()==null)break;
        }
    }

    private static void pruneEvals(){long cutoff=System.currentTimeMillis()-60_000L;EVALS.entrySet().removeIf(e->e.getValue().createdAt<cutoff);}
    private static Object nullable(String value){return value==null?JSONObject.NULL:value;}
    private static String cut(String value){return value!=null&&value.length()>2048?value.substring(0,2048):value;}
    private static String safe(ThrowingString action){try{return action.get();}catch(Throwable ignored){return null;}}
    private static JSONObject ok()throws Exception{return new JSONObject().put("ok",true);}private static JSONObject error(String c,String m)throws Exception{return new JSONObject().put("ok",false).put("error",new JSONObject().put("code",c).put("message",m));}
    private interface ThrowingString{String get()throws Throwable;}
    private record EvalResult(boolean done,String value,String error,long createdAt){}
}
