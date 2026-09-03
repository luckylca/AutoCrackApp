package com.luckylca.autocrack.runtime.shared;

import android.content.Context;
import com.luckylca.runtimeinspector.runtime.InspectorPrimitives;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Stable capability dispatcher. Toolpack CLIs are intentionally only user interfaces over this API. */
public final class RuntimeDispatcher {
    public static final String VERSION = "1.0.0";
    private static final Set<String> LEGACY = Set.of("windows","view_tree","view_at","view_action");
    private RuntimeDispatcher() {}

    public static JSONObject execute(Context context, JSONObject request) {
        String kind = request.optString("kind", "");
        if (requiresMainThread(kind)) {
            return RuntimeThreading.callOnMain(() -> executeDirect(context, request));
        }
        return executeDirect(context, request);
    }

    private static JSONObject executeDirect(Context context, JSONObject request) {
        try {
            String kind = request.optString("kind", "");
            if (kind.isBlank()) return error("KIND_REQUIRED", "kind is required");
            if ("runtime.capabilities".equals(kind)) return capabilities();
            if (UiIntrospector.supports(kind)) return UiIntrospector.execute(request);
            if (RuntimeIntrospector.supports(kind)) return RuntimeIntrospector.execute(context, request);
            if (MemoryIntrospector.supports(kind)) return MemoryIntrospector.execute(context, request);
            if (WebControlIntrospector.supports(kind)) return WebControlIntrospector.execute(context, request);
            if (LEGACY.contains(kind) && InspectorPrimitives.supports(kind)) return InspectorPrimitives.execute(request);
            return error("UNSUPPORTED_KIND", kind);
        } catch (IllegalArgumentException error) {
            String text = String.valueOf(error.getMessage());
            if (text.startsWith("STALE_HANDLE") || text.startsWith("STALE_OR_NON_VIEW_HANDLE")) return error("STALE_HANDLE", text);
            return error("INVALID_ARGUMENT", text);
        } catch (Throwable error) {
            return error("RUNTIME_ERROR", error.toString());
        }
    }

    private static boolean requiresMainThread(String kind) {
        if (kind == null || kind.isBlank()) return false;
        if (kind.startsWith("ui.")) return true;
        if (kind.startsWith("webview.")) return true;
        return Set.of(
                "control.secure.status",
                "control.secure.disable",
                "control.activity.start",
                "control.process.kill",
                "windows", "view_tree", "view_at", "view_action"
        ).contains(kind);
    }

    public static JSONObject capabilities() throws Exception {
        JSONArray supported = new JSONArray();
        for (String capability : List.of(
                "ui.windows","ui.tree","ui.at","ui.find","ui.props","ui.parent","ui.children","ui.siblings","ui.listeners","ui.stack","ui.image","ui.image.result","ui.action","ui.compose.status","ui.compose.tree",
                "runtime.process","runtime.activities","runtime.declared_activities","runtime.classloaders","runtime.class.search","runtime.class.describe",
                "object.describe","object.fields","object.dump","object.pin","object.release","object.clear_session",
                "memory.maps","memory.modules","memory.native.modules","memory.read","memory.native.probe","memory.dladdr","memory.module.dump","memory.module.file_dump","memory.elf.info","memory.elf.symbols","memory.elf.relocations","memory.elf.dynamic","memory.dex.list","memory.dex.art_probe","memory.dex.art_pointer_probe","memory.dex.info","memory.dex.apk_index","memory.dex.strings","memory.dex.classes","memory.dex.fields","memory.dex.methods","memory.dex.class_data","memory.dex.scan","memory.dex.dump","memory.assets.list","memory.assets.pull","memory.xml.pull","memory.xml.block_probe","memory.xml.binary","memory.xml.axml_decode","memory.xml.axml_text","memory.apk.entries","memory.apk.pull",
                "webview.list","webview.info","webview.debug","webview.eval","webview.eval.result","webview.load_url","webview.reload","webview.go_back","webview.go_forward","webview.clear_cache",
                "control.secure.status","control.secure.diagnose","control.secure.disable","control.so.inject","control.so.diagnose","control.so.dlopen","control.so.android_dlopen_ext","control.so.dlsym","control.activity.start","control.process.kill","control.object.field.set","control.object.method.call",
                "hook.reload","hook.inspect")) supported.put(capability);
        JSONArray partial = new JSONArray()
                .put(new JSONObject().put("capability","memory.dex.dump").put("reason","file-backed strategy is stable; ART pointer reconstruction remains Android-version-gated"))
                .put(new JSONObject().put("capability","memory.dex.art_pointer_probe").put("reason","mCookie pointers can be mapped and scanned, but ART object offsets remain version-gated"))
                .put(new JSONObject().put("capability","memory.xml.pull").put("reason","logical Resources.getXml is supported; binary XmlBlock/ResXMLTree recovery remains native/version-gated"))
                .put(new JSONObject().put("capability","memory.xml.block_probe").put("reason","XmlResourceParser/XmlBlock object-shape probing is supported; native ResXMLTree byte recovery remains version-gated"))
                .put(new JSONObject().put("capability","ui.compose.tree").put("reason","AndroidComposeView detection and reflective SemanticsOwner tree probing are supported best-effort; Compose internals remain version-dependent"))
                .put(new JSONObject().put("capability","ui.image").put("reason","View.draw, TextureView.getBitmap and SurfaceView PixelCopy(Window) are implemented; secure/DRM producer surfaces may still refuse capture"))
                .put(new JSONObject().put("capability","control.so.inject").put("reason","System.load is stable; native dlopen is available when the JNI bridge loads; linker namespace bypass is still not embedded"));
        return ok().put("version", VERSION).put("supported", supported).put("partial", partial)
                .put("limits", new JSONObject().put("object_handles",ObjectRegistry.MAX_HANDLES)
                        .put("object_pinned_handles",ObjectRegistry.MAX_PINNED_HANDLES)
                        .put("object_ttl_ms",ObjectRegistry.DEFAULT_TTL_MS).put("view_nodes",4000)
                        .put("class_search",10000).put("inline_dump_bytes",4*1024*1024))
                .put("threading", new JSONObject().put("ui_main_looper", true)
                        .put("reflection_worker", true).put("main_call_timeout_ms", RuntimeThreading.MAIN_CALL_TIMEOUT_MS));
    }

    private static JSONObject ok() throws Exception { return new JSONObject().put("ok", true); }
    private static JSONObject error(String code, String message) {
        try { return new JSONObject().put("ok", false).put("error", new JSONObject().put("code", code).put("message", message == null ? "" : message)); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
}
