package com.luckylca.autocrack.runtime.shared;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import java.lang.reflect.Field;
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
                "webview.list", "webview.info", "webview.debug", "webview.eval", "webview.eval.result",
                "control.secure.status", "control.secure.disable", "control.so.inject",
                "control.activity.start", "control.process.kill").contains(kind);
    }

    public static JSONObject execute(Context context, JSONObject request) throws Exception {
        return switch (request.getString("kind")) {
            case "webview.list" -> webviews();
            case "webview.info" -> webviewInfo(request);
            case "webview.debug" -> webviewDebug(request);
            case "webview.eval" -> webviewEval(request);
            case "webview.eval.result" -> webviewEvalResult(request);
            case "control.secure.status" -> secureStatus();
            case "control.secure.disable" -> secureDisable();
            case "control.so.inject" -> injectSo(request);
            case "control.activity.start" -> startActivity(context, request);
            case "control.process.kill" -> killProcess(request);
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
                .put("title", nullable(safe(() -> web.getTitle())));
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

    private static JSONObject secureStatus() throws Exception {
        JSONArray windows = new JSONArray(); int secure = 0;
        for (ActivityRegistry.ActivitySnapshot snapshot : ActivityRegistry.get().snapshot()) {
            Activity activity = snapshot.activity(); Window window = activity.getWindow(); if (window == null) continue;
            int flags = window.getAttributes().flags; boolean value = (flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
            if (value) secure++;
            windows.put(new JSONObject().put("activity", activity.getClass().getName())
                    .put("activity_handle", ObjectRegistry.get().put(activity, false, "activity"))
                    .put("window_handle", ObjectRegistry.get().put(window, false, "window"))
                    .put("flags", flags).put("flag_secure", value));
        }
        return ok().put("secure_window_count", secure).put("windows", windows)
                .put("view_surface_cause_supported", false)
                .put("scope_note", "Stable strategy identifies Window.FLAG_SECURE. Vendor/private secure SurfaceControl or DRM surfaces are reported as outside this capability.");
    }

    private static JSONObject secureDisable() throws Exception {
        JSONArray changed = new JSONArray();
        for (ActivityRegistry.ActivitySnapshot snapshot : ActivityRegistry.get().snapshot()) {
            Activity activity = snapshot.activity(); Window window = activity.getWindow(); if (window == null) continue;
            int before = window.getAttributes().flags;
            if ((before & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                changed.put(new JSONObject().put("activity", activity.getClass().getName())
                        .put("window_handle", ObjectRegistry.get().put(window, false, "window"))
                        .put("flags_before", before).put("flags_after", window.getAttributes().flags));
            }
        }
        return ok().put("changed", changed.length()).put("windows", changed)
                .put("persistent", false).put("strategy", "Window.clearFlags(FLAG_SECURE)");
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
    private static String safe(ThrowingString action){try{return action.get();}catch(Throwable ignored){return null;}}
    private static JSONObject ok()throws Exception{return new JSONObject().put("ok",true);}private static JSONObject error(String c,String m)throws Exception{return new JSONObject().put("ok",false).put("error",new JSONObject().put("code",c).put("message",m));}
    private interface ThrowingString{String get()throws Throwable;}
    private record EvalResult(boolean done,String value,String error,long createdAt){}
}
