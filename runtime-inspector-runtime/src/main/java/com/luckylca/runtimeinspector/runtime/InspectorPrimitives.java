package com.luckylca.runtimeinspector.runtime;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.graphics.Matrix;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.TextView;
import com.luckylca.autocrack.runtime.shared.ObjectRegistry;
import com.luckylca.autocrack.runtime.shared.ViewCreationTracker;
import com.luckylca.autocrack.runtime.shared.WindowRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

@SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
public final class InspectorPrimitives {
    private static final int MAX_ROOTS = 64;
    private static final int MAX_NODES = 4000;
    private static final int MAX_STRING = 2048;
    private static final String[] LISTENERS = {
            "mOnClickListener", "mOnLongClickListener", "mOnTouchListener",
            "mOnKeyListener", "mOnFocusChangeListener"
    };

    private InspectorPrimitives() {}

    public static boolean supports(String kind) {
        return Set.of("windows", "view_tree", "view_at", "view_action").contains(kind);
    }

    public static JSONObject execute(JSONObject request) throws Exception {
        return switch (request.getString("kind")) {
            case "windows" -> windows(request.optInt("max_roots", MAX_ROOTS));
            case "view_tree" -> tree(request.optInt("max_nodes", MAX_NODES), request.optBoolean("include_listeners"));
            case "view_at" -> at(request.getInt("x"), request.getInt("y"), request.optInt("max_nodes", MAX_NODES), request.optBoolean("include_listeners"), request.optBoolean("include_hidden", false));
            case "view_action" -> action(request);
            default -> error("UNSUPPORTED_KIND", request.optString("kind"));
        };
    }

    private static JSONObject windows(int maxRoots) throws Exception {
        List<View> roots = roots(Math.max(1, Math.min(maxRoots, MAX_ROOTS)));
        JSONArray values = new JSONArray();
        for (int i = 0; i < roots.size(); i++) {
            View view = roots.get(i);
            values.put(new JSONObject()
                    .put("index", i).put("node_id", nodeId(view)).put("handle", handle(view))
                    .put("class", view.getClass().getName())
                    .put("bounds", bounds(view)).put("shown", view.isShown())
                    .put("attached", view.isAttachedToWindow())
                    .put("layout_params", WindowRegistry.get().describeLayoutParams(view)));
        }
        return ok().put("root_count", roots.size()).put("roots", values);
    }

    private static JSONObject tree(int maxNodes, boolean listeners) throws Exception {
        int limit = Math.max(1, Math.min(maxNodes, MAX_NODES));
        JSONArray nodes = new JSONArray();
        Budget budget = new Budget(limit);
        Map<View, String> ids = new IdentityHashMap<>();
        List<View> roots = roots(MAX_ROOTS);
        for (int i = 0; i < roots.size() && budget.room(); i++) {
            walk(roots.get(i), null, i, 0, nodes, budget, ids, listeners);
        }
        return ok().put("root_count", roots.size()).put("node_count", nodes.length())
                .put("truncated", budget.truncated).put("nodes", nodes);
    }

    private static JSONObject at(int x, int y, int maxNodes, boolean listeners, boolean includeHidden) throws Exception {
        int limit = Math.max(1, Math.min(maxNodes, MAX_NODES));
        List<Hit> hits = new ArrayList<>();
        Budget budget = new Budget(limit);
        Map<View, String> ids = new IdentityHashMap<>();
        List<View> roots = roots(MAX_ROOTS);
        for (int i = 0; i < roots.size() && budget.room(); i++) {
            hitWalk(roots.get(i), null, i, 0, i, 0, x, y, hits, budget, ids, includeHidden);
        }
        hits.sort((a, b) -> {
            int root = Integer.compare(b.rootIndex, a.rootIndex);
            if (root != 0) return root;
            int z = Float.compare(b.view.getZ(), a.view.getZ());
            if (z != 0) return z;
            int draw = Integer.compare(b.drawOrder, a.drawOrder);
            if (draw != 0) return draw;
            int depth = Integer.compare(b.depth, a.depth);
            if (depth != 0) return depth;
            long aa = (long) Math.max(1, a.view.getWidth()) * Math.max(1, a.view.getHeight());
            long bb = (long) Math.max(1, b.view.getWidth()) * Math.max(1, b.view.getHeight());
            return Long.compare(aa, bb);
        });
        JSONArray result = new JSONArray();
        for (Hit hit : hits) result.put(describe(hit.view, hit.parentId, hit.index, hit.depth, ids, listeners));
        return ok().put("x", x).put("y", y).put("candidate_count", result.length())
                .put("truncated", budget.truncated).put("candidates", result);
    }

    private static JSONObject action(JSONObject request) throws Exception {
        View target = null;
        String wanted = request.optString("node_id", null);
        if (wanted != null) target = findByNodeId(wanted);
        if (target == null && request.has("x") && request.has("y")) {
            JSONObject hit = at(request.getInt("x"), request.getInt("y"), MAX_NODES, false, false);
            JSONArray candidates = hit.getJSONArray("candidates");
            if (candidates.length() > 0) target = findByNodeId(candidates.getJSONObject(0).getString("node_id"));
        }
        if (target == null) return error("VIEW_NOT_FOUND", "No matching View");
        JSONObject action = request.getJSONObject("action");
        String type = action.getString("type");
        switch (type) {
            case "set_visibility" -> target.setVisibility(switch (action.optString("value", "visible")) {
                case "gone" -> View.GONE; case "invisible" -> View.INVISIBLE; default -> View.VISIBLE;
            });
            case "set_text" -> {
                if (!(target instanceof TextView text)) return error("TYPE_MISMATCH", "Target is not TextView");
                text.setText(action.optString("value", ""));
            }
            case "set_text_color" -> {
                if (!(target instanceof TextView text)) return error("TYPE_MISMATCH", "Target is not TextView");
                text.setTextColor(action.getInt("value"));
            }
            case "set_background_color" -> target.setBackgroundColor(action.getInt("value"));
            case "set_alpha" -> target.setAlpha((float) action.getDouble("value"));
            case "remove_view" -> {
                if (!(target.getParent() instanceof ViewGroup parent)) return error("NO_PARENT", "Parent is not ViewGroup");
                parent.removeView(target);
            }
                        case "perform_click" -> {
                if (!target.performClick()) return error("CLICK_REJECTED", "View did not handle performClick");
            }
case "webview_eval_js" -> {
                if (!(target instanceof WebView web)) return error("TYPE_MISMATCH", "Target is not WebView");
                web.evaluateJavascript(action.optString("script", ""), null);
            }
            default -> { return error("UNSUPPORTED_ACTION", type); }
        }
        return ok().put("node_id", nodeId(target)).put("action", type);
    }

    private static void walk(View view, String parentId, int index, int depth, JSONArray out,
            Budget budget, Map<View, String> ids, boolean listeners) throws Exception {
        if (!budget.take()) return;
        String id = ids.computeIfAbsent(view, InspectorPrimitives::nodeId);
        out.put(describe(view, parentId, index, depth, ids, listeners));
        if (view instanceof ViewGroup group) {
            List<Integer> order = childDrawingOrder(group);
            for (int draw = 0; draw < order.size() && budget.room(); draw++) {
                int childIndex = order.get(draw);
                walk(group.getChildAt(childIndex), id, childIndex, depth + 1, out, budget, ids, listeners);
            }
        }
    }

    private static void hitWalk(View view, String parentId, int index, int rootIndex, int depth, int drawOrder,
            int x, int y, List<Hit> hits, Budget budget, Map<View, String> ids, boolean includeHidden) {
        if (!budget.take()) return;
        String id = ids.computeIfAbsent(view, InspectorPrimitives::nodeId);
        if (containsScreenPoint(view, x, y, includeHidden)) {
            hits.add(new Hit(view, parentId, index, rootIndex, depth, drawOrder));
        }
        if (view instanceof ViewGroup group) {
            List<Integer> order = childDrawingOrder(group);
            for (int draw = 0; draw < order.size() && budget.room(); draw++) {
                int childIndex = order.get(draw);
                hitWalk(group.getChildAt(childIndex), id, childIndex, rootIndex, depth + 1, draw, x, y, hits, budget, ids, includeHidden);
            }
        }
    }

    private static boolean containsScreenPoint(View view, int x, int y, boolean includeHidden) {
        try {
            if (view.getWidth() <= 0 || view.getHeight() <= 0) return false;
            if (!includeHidden && (!view.isShown() || view.getAlpha() <= 0f)) return false;
            Rect visible = new Rect();
            boolean globallyVisible = view.getGlobalVisibleRect(visible);
            if (!includeHidden && (!globallyVisible || !visible.contains(x, y))) return false;
            if (includeHidden && !roughScreenBounds(view).contains(x, y)) return false;
            float[] point = new float[]{x, y};
            Matrix matrix = new Matrix();
            view.transformMatrixToGlobal(matrix);
            int[] root = new int[2];
            View rootView = view.getRootView();
            if (rootView != null) rootView.getLocationOnScreen(root);
            matrix.postTranslate(root[0], root[1]);
            Matrix inverse = new Matrix();
            if (!matrix.invert(inverse)) return false;
            inverse.mapPoints(point);
            return point[0] >= 0f && point[1] >= 0f && point[0] < view.getWidth() && point[1] < view.getHeight();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Rect roughScreenBounds(View view) {
        int[] location = new int[2];
        try { view.getLocationOnScreen(location); }
        catch (Throwable ignored) { return new Rect(); }
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private static List<Integer> childDrawingOrder(ViewGroup group) {
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) order.add(i);
        order.sort((a, b) -> {
            int z = Float.compare(group.getChildAt(a).getZ(), group.getChildAt(b).getZ());
            if (z != 0) return z;
            return Integer.compare(a, b);
        });
        return order;
    }

    private static JSONObject describe(View view, String parentId, int index, int depth,
            Map<View, String> ids, boolean listeners) throws Exception {
        JSONObject value = new JSONObject()
                .put("node_id", ids.computeIfAbsent(view, InspectorPrimitives::nodeId))
                .put("handle", handle(view))
                .put("parent_id", parentId == null ? JSONObject.NULL : parentId)
                .put("index", index).put("depth", depth)
                .put("class", view.getClass().getName())
                .put("resource_id", view.getId()).put("resource_name", resourceName(view))
                .put("bounds", bounds(view)).put("width", view.getWidth()).put("height", view.getHeight())
                .put("visibility", view.getVisibility()).put("shown", view.isShown())
                .put("enabled", view.isEnabled()).put("clickable", view.isClickable())
                .put("long_clickable", view.isLongClickable()).put("alpha", view.getAlpha());
        if (view.getContentDescription() != null) value.put("content_description", cut(view.getContentDescription().toString()));
        if (view.getTag() != null) value.put("tag", cut(String.valueOf(view.getTag())));
        if (view instanceof TextView text) {
            value.put("text", cut(String.valueOf(text.getText())))
                    .put("hint", text.getHint() == null ? JSONObject.NULL : cut(String.valueOf(text.getHint())))
                    .put("text_size_px", text.getTextSize()).put("text_color", text.getCurrentTextColor());
        }
        if (view instanceof WebView web) {
            JSONObject info = new JSONObject();
            try { info.put("url", web.getUrl()); } catch (Throwable error) { info.put("url_error", error.toString()); }
            value.put("webview", info);
        }
        value.put("translation_x", view.getTranslationX()).put("translation_y", view.getTranslationY())
                .put("scale_x", view.getScaleX()).put("scale_y", view.getScaleY())
                .put("rotation", view.getRotation()).put("elevation", view.getElevation()).put("z", view.getZ())
                .put("padding", new JSONArray().put(view.getPaddingLeft()).put(view.getPaddingTop()).put(view.getPaddingRight()).put(view.getPaddingBottom()));
        ViewCreationTracker.Record creation = ViewCreationTracker.get().get(view);
        if (creation != null) value.put("creation_stack_available", creation.construction() != null || creation.inflate() != null || creation.add() != null);
        if (listeners) value.put("listeners", listeners(view));
        return value;
    }

    private static JSONObject listeners(View view) throws Exception {
        JSONObject values = new JSONObject();
        try {
            Field infoField = View.class.getDeclaredField("mListenerInfo");
            infoField.setAccessible(true);
            Object info = infoField.get(view);
            if (info == null) return values;
            Class<?> type = Class.forName("android.view.View$ListenerInfo");
            for (String name : LISTENERS) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    Object listener = field.get(info);
                    if (listener != null) {
                        values.put(name, listener.getClass().getName());
                        values.put(name + "_handle", ObjectRegistry.get().put(listener, false, "ui"));
                    }
                } catch (Throwable error) {
                    values.put(name + "_error", error.toString());
                }
            }
        } catch (Throwable error) {
            values.put("error", error.toString());
        }
        return values;
    }

    private static View findByNodeId(String id) {
        for (View root : roots(MAX_ROOTS)) {
            View found = find(root, id);
            if (found != null) return found;
        }
        return null;
    }

    private static View find(View view, String id) {
        if (nodeId(view).equals(id)) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<View> roots(int max) {
        LinkedHashSet<View> all = new LinkedHashSet<>(WindowRootRegistry.snapshot());
        try {
            Class<?> globalType = Class.forName("android.view.WindowManagerGlobal");
            Method get = globalType.getDeclaredMethod("getInstance");
            get.setAccessible(true);
            Object global = get.invoke(null);
            Field views = globalType.getDeclaredField("mViews");
            views.setAccessible(true);
            Object raw = views.get(global);
            if (raw instanceof List<?> list) for (Object item : list) if (item instanceof View view) all.add(view);
        } catch (Throwable ignored) {}
        List<View> result = new ArrayList<>(all);
        if (result.size() > max) return new ArrayList<>(result.subList(0, max));
        return result;
    }

    private static JSONArray bounds(View view) {
        JSONArray value = new JSONArray();
        int[] location = new int[2];
        try {
            view.getLocationOnScreen(location);
            return value.put(location[0]).put(location[1])
                    .put(location[0] + view.getWidth()).put(location[1] + view.getHeight());
        } catch (Throwable ignored) {
            return value.put(0).put(0).put(0).put(0);
        }
    }

    private static Object resourceName(View view) {
        if (view.getId() == View.NO_ID) return JSONObject.NULL;
        try { return view.getResources().getResourceName(view.getId()); }
        catch (Throwable ignored) { return Integer.toString(view.getId()); }
    }

    private static String handle(View view) { return ObjectRegistry.get().put(view, false, "ui"); }
    private static String nodeId(View view) { return "v_" + Integer.toHexString(System.identityHashCode(view)); }
    private static String cut(String value) { return value != null && value.length() > MAX_STRING ? value.substring(0, MAX_STRING) : value; }
    private static JSONObject ok() throws Exception { return new JSONObject().put("ok", true); }
    private static JSONObject error(String code, String message) throws Exception { return new JSONObject().put("ok", false).put("error", new JSONObject().put("code", code).put("message", message)); }

    private static final class Budget {
        final int max; int used; boolean truncated;
        Budget(int max) { this.max = max; }
        boolean room() { if (used >= max) truncated = true; return used < max; }
        boolean take() { if (!room()) return false; used++; return true; }
    }

    private record Hit(View view, String parentId, int index, int rootIndex, int depth, int drawOrder) {}
}
