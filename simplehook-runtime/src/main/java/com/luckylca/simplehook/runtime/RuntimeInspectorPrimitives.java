package com.luckylca.simplehook.runtime;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Clean-room Android runtime-inspector primitives.
 *
 * <p>This class intentionally does not depend on Layout Inspect internals. It implements the public
 * idea with Android View APIs plus bounded reflection. RuntimeEngine dispatches selected inspect
 * requests here through the same request/result channel used by class inspection.</p>
 */
@SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
final class RuntimeInspectorPrimitives {
    static final int DEFAULT_MAX_ROOTS = 64;
    static final int DEFAULT_MAX_NODES = 3000;
    private static final int MAX_STRING = 2048;
    private static final String[] LISTENER_FIELDS = new String[] {
            "mOnClickListener",
            "mOnLongClickListener",
            "mOnTouchListener",
            "mOnKeyListener",
            "mOnFocusChangeListener"
    };

    private RuntimeInspectorPrimitives() {}

    static boolean supports(String kind) {
        return "windows".equals(kind)
                || "view_tree".equals(kind)
                || "view_at".equals(kind)
                || "view_action".equals(kind);
    }

    static JSONObject executeRequest(JSONObject request) throws JSONException {
        String kind = request.getString("kind");
        return switch (kind) {
            case "windows" -> inspectWindows(request.optInt("max_roots", DEFAULT_MAX_ROOTS));
            case "view_tree" -> inspectViewTree(
                    request.optInt("max_nodes", DEFAULT_MAX_NODES),
                    request.optBoolean("include_listeners", false));
            case "view_at" -> inspectViewAt(
                    request.getInt("x"),
                    request.getInt("y"),
                    request.optInt("max_nodes", DEFAULT_MAX_NODES),
                    request.optBoolean("include_listeners", true));
            case "view_action" -> applyViewAction(selectTargetView(request), request.getJSONObject("action"));
            default -> throw new JSONException("Unsupported inspector kind: " + kind);
        };
    }

    static JSONObject inspectWindows(int maxRoots) throws JSONException {
        List<View> roots = rootViews(Math.min(Math.max(maxRoots, 1), DEFAULT_MAX_ROOTS));
        JSONArray array = new JSONArray();
        for (int i = 0; i < roots.size(); i++) {
            View root = roots.get(i);
            array.put(new JSONObject()
                    .put("root_id", "root_" + i)
                    .put("class", root.getClass().getName())
                    .put("bounds_screen", bounds(root))
                    .put("shown", root.isShown())
                    .put("attached", root.isAttachedToWindow())
                    .put("node_id", nodeId(root)));
        }
        return new JSONObject().put("ok", true).put("root_count", roots.size()).put("roots", array);
    }

    static JSONObject inspectViewTree(int maxNodes, boolean includeListeners) throws JSONException {
        int boundedMaxNodes = Math.min(Math.max(maxNodes, 1), DEFAULT_MAX_NODES);
        List<View> roots = rootViews(DEFAULT_MAX_ROOTS);
        JSONArray rootArray = new JSONArray();
        NodeBudget budget = new NodeBudget(boundedMaxNodes);
        Map<View, String> ids = new IdentityHashMap<>();
        for (int i = 0; i < roots.size() && budget.hasRoom(); i++) {
            rootArray.put(describeSubtree(roots.get(i), null, i, 0, budget, ids, includeListeners));
        }
        return new JSONObject()
                .put("ok", true)
                .put("root_count", roots.size())
                .put("returned_nodes", budget.used)
                .put("truncated", budget.truncated)
                .put("roots", rootArray);
    }

    static JSONObject inspectViewAt(int x, int y, int maxNodes, boolean includeListeners) throws JSONException {
        int boundedMaxNodes = Math.min(Math.max(maxNodes, 1), DEFAULT_MAX_NODES);
        List<Candidate> candidates = new ArrayList<>();
        NodeBudget budget = new NodeBudget(boundedMaxNodes);
        Map<View, String> ids = new IdentityHashMap<>();
        List<View> roots = rootViews(DEFAULT_MAX_ROOTS);
        for (int i = 0; i < roots.size() && budget.hasRoom(); i++) {
            collectHitCandidates(roots.get(i), null, i, 0, x, y, candidates, budget, ids);
        }
        Collections.sort(candidates, (left, right) -> {
            int depth = Integer.compare(right.depth, left.depth);
            if (depth != 0) return depth;
            return Integer.compare(right.drawOrder, left.drawOrder);
        });
        JSONArray array = new JSONArray();
        for (Candidate candidate : candidates) {
            array.put(describeView(candidate.view, candidate.parentId, candidate.indexInParent,
                    candidate.depth, ids, includeListeners));
        }
        return new JSONObject()
                .put("ok", true)
                .put("x", x)
                .put("y", y)
                .put("candidate_count", candidates.size())
                .put("truncated", budget.truncated)
                .put("candidates", array);
    }

    static JSONObject applyViewAction(View view, JSONObject action) throws JSONException {
        String type = action.getString("type");
        switch (type) {
            case "set_visibility" -> setVisibility(view, action.optString("value", "visible"));
            case "remove_view" -> removeView(view);
            case "set_text" -> {
                if (!(view instanceof TextView textView)) throw new JSONException("Target is not TextView");
                textView.setText(action.optString("value", ""));
            }
            case "set_text_color" -> {
                if (!(view instanceof TextView textView)) throw new JSONException("Target is not TextView");
                textView.setTextColor(action.getInt("value"));
            }
            case "set_text_size_sp" -> {
                if (!(view instanceof TextView textView)) throw new JSONException("Target is not TextView");
                textView.setTextSize((float) action.getDouble("value"));
            }
            case "set_padding" -> view.setPadding(action.optInt("left"), action.optInt("top"),
                    action.optInt("right"), action.optInt("bottom"));
            case "set_size" -> setSize(view, action.optInt("width", layoutWidth(view)),
                    action.optInt("height", layoutHeight(view)));
            case "set_margin" -> setMargin(view, action.optInt("left"), action.optInt("top"),
                    action.optInt("right"), action.optInt("bottom"));
            case "webview_eval_js" -> {
                if (!(view instanceof WebView webView)) throw new JSONException("Target is not WebView");
                webView.evaluateJavascript(action.optString("script", ""), null);
            }
            case "webview_load_url" -> {
                if (!(view instanceof WebView webView)) throw new JSONException("Target is not WebView");
                webView.loadUrl(action.getString("url"));
            }
            default -> throw new JSONException("Unsupported action type: " + type);
        }
        return new JSONObject().put("ok", true).put("action", type).put("node_id", nodeId(view));
    }

    private static void setVisibility(View view, String value) throws JSONException {
        switch (value) {
            case "visible" -> view.setVisibility(View.VISIBLE);
            case "invisible" -> view.setVisibility(View.INVISIBLE);
            case "gone" -> view.setVisibility(View.GONE);
            default -> throw new JSONException("Unsupported visibility: " + value);
        }
    }

    private static void removeView(View view) throws JSONException {
        if (!(view.getParent() instanceof ViewGroup parent)) throw new JSONException("Parent is not ViewGroup");
        parent.removeView(view);
    }

    private static void setSize(View view, int width, int height) throws JSONException {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) throw new JSONException("View has no LayoutParams");
        params.width = width;
        params.height = height;
        view.setLayoutParams(params);
    }

    private static void setMargin(View view, int left, int top, int right, int bottom) throws JSONException {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams marginParams)) {
            throw new JSONException("LayoutParams is not MarginLayoutParams");
        }
        marginParams.setMargins(left, top, right, bottom);
        view.setLayoutParams(marginParams);
    }

    private static JSONObject describeSubtree(View view, String parentId, int index, int depth,
            NodeBudget budget, Map<View, String> ids, boolean includeListeners) throws JSONException {
        budget.take();
        JSONObject node = describeView(view, parentId, index, depth, ids, includeListeners);
        if (view instanceof ViewGroup group && budget.hasRoom()) {
            JSONArray children = new JSONArray();
            int count = group.getChildCount();
            for (int i = 0; i < count && budget.hasRoom(); i++) {
                children.put(describeSubtree(group.getChildAt(i), ids.get(view), i, depth + 1,
                        budget, ids, includeListeners));
            }
            node.put("child_count", count).put("children", children);
        }
        return node;
    }

    private static JSONObject describeView(View view, String parentId, int index, int depth,
            Map<View, String> ids, boolean includeListeners) throws JSONException {
        String id = ids.computeIfAbsent(view, RuntimeInspectorPrimitives::nodeId);
        JSONObject node = new JSONObject()
                .put("node_id", id)
                .put("parent_id", parentId == null ? JSONObject.NULL : parentId)
                .put("index_in_parent", index)
                .put("depth", depth)
                .put("class", view.getClass().getName())
                .put("identity_hash", System.identityHashCode(view))
                .put("resource_id", view.getId())
                .put("resource_name", resourceName(view))
                .put("bounds_screen", bounds(view))
                .put("width", view.getWidth())
                .put("height", view.getHeight())
                .put("visibility", view.getVisibility())
                .put("shown", view.isShown())
                .put("enabled", view.isEnabled())
                .put("clickable", view.isClickable())
                .put("long_clickable", view.isLongClickable())
                .put("focusable", view.isFocusable())
                .put("alpha", view.getAlpha());
        Object tag = view.getTag();
        if (tag != null) node.put("tag", safeString(tag));
        CharSequence cd = view.getContentDescription();
        if (cd != null) node.put("content_description", truncate(cd.toString()));
        if (view instanceof TextView textView) describeTextView(node, textView);
        if (view instanceof ImageView) node.put("image_view", true);
        if (view instanceof WebView webView) describeWebView(node, webView);
        if (view instanceof AdapterView<?> adapterView) node.put("adapter", describeAdapter(adapterView));
        if (includeListeners) node.put("listeners", inspectListeners(view));
        return node;
    }

    private static void describeTextView(JSONObject node, TextView view) throws JSONException {
        node.put("text", truncate(String.valueOf(view.getText())))
                .put("hint", view.getHint() == null ? JSONObject.NULL : truncate(String.valueOf(view.getHint())))
                .put("text_size_px", view.getTextSize())
                .put("text_color", view.getCurrentTextColor());
    }

    private static void describeWebView(JSONObject node, WebView view) throws JSONException {
        JSONObject web = new JSONObject();
        try { web.put("url", view.getUrl() == null ? JSONObject.NULL : truncate(view.getUrl())); }
        catch (Throwable error) { web.put("url_error", error.toString()); }
        try { web.put("user_agent", truncate(view.getSettings().getUserAgentString())); }
        catch (Throwable error) { web.put("user_agent_error", error.toString()); }
        node.put("webview", web);
    }

    private static JSONObject describeAdapter(AdapterView<?> view) throws JSONException {
        JSONObject result = new JSONObject();
        try { result.put("count", view.getAdapter() == null ? 0 : view.getAdapter().getCount()); }
        catch (Throwable error) { result.put("count_error", error.toString()); }
        result.put("selected_position", view.getSelectedItemPosition());
        return result;
    }

    private static JSONObject inspectListeners(View view) throws JSONException {
        JSONObject result = new JSONObject();
        try {
            Field infoField = View.class.getDeclaredField("mListenerInfo");
            infoField.setAccessible(true);
            Object info = infoField.get(view);
            if (info == null) return result;
            Class<?> type = Class.forName("android.view.View$ListenerInfo");
            for (String fieldName : LISTENER_FIELDS) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object listener = field.get(info);
                    if (listener != null) result.put(fieldName, listener.getClass().getName());
                } catch (Throwable fieldError) {
                    result.put(fieldName + "_error", fieldError.toString());
                }
            }
        } catch (Throwable error) {
            result.put("error", error.toString());
        }
        return result;
    }

    private static void collectHitCandidates(View view, String parentId, int index, int depth,
            int x, int y, List<Candidate> out, NodeBudget budget, Map<View, String> ids) {
        if (!budget.hasRoom()) return;
        budget.take();
        String id = ids.computeIfAbsent(view, RuntimeInspectorPrimitives::nodeId);
        Rect rect = new Rect();
        boolean hit = false;
        try { hit = view.isShown() && view.getGlobalVisibleRect(rect) && rect.contains(x, y); }
        catch (Throwable ignored) {}
        if (hit) out.add(new Candidate(view, parentId, index, depth, out.size()));
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount() && budget.hasRoom(); i++) {
                collectHitCandidates(group.getChildAt(i), id, i, depth + 1, x, y, out, budget, ids);
            }
        }
    }

    private static View selectTargetView(JSONObject request) throws JSONException {
        String nodeId = request.optString("node_id", null);
        int maxNodes = request.optInt("max_nodes", DEFAULT_MAX_NODES);
        if (nodeId != null && !nodeId.isBlank()) {
            View byNode = findViewByNodeId(nodeId, maxNodes);
            if (byNode != null) return byNode;
            throw new JSONException("View node not found: " + nodeId);
        }
        if (request.has("x") && request.has("y")) {
            View byPoint = topHitView(request.getInt("x"), request.getInt("y"), maxNodes);
            if (byPoint != null) return byPoint;
            throw new JSONException("No visible View hit at requested coordinates");
        }
        throw new JSONException("view_action requires node_id or x/y");
    }

    private static View findViewByNodeId(String nodeId, int maxNodes) {
        NodeBudget budget = new NodeBudget(Math.min(Math.max(maxNodes, 1), DEFAULT_MAX_NODES));
        for (View root : rootViews(DEFAULT_MAX_ROOTS)) {
            View found = findViewByNodeId(root, nodeId, budget);
            if (found != null || !budget.hasRoom()) return found;
        }
        return null;
    }

    private static View findViewByNodeId(View view, String nodeId, NodeBudget budget) {
        if (!budget.hasRoom()) return null;
        budget.take();
        if (nodeId.equals(nodeId(view))) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount() && budget.hasRoom(); i++) {
                View found = findViewByNodeId(group.getChildAt(i), nodeId, budget);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View topHitView(int x, int y, int maxNodes) {
        List<Candidate> candidates = new ArrayList<>();
        NodeBudget budget = new NodeBudget(Math.min(Math.max(maxNodes, 1), DEFAULT_MAX_NODES));
        Map<View, String> ids = new IdentityHashMap<>();
        List<View> roots = rootViews(DEFAULT_MAX_ROOTS);
        for (int i = 0; i < roots.size() && budget.hasRoom(); i++) {
            collectHitCandidates(roots.get(i), null, i, 0, x, y, candidates, budget, ids);
        }
        Collections.sort(candidates, (left, right) -> {
            int depth = Integer.compare(right.depth, left.depth);
            if (depth != 0) return depth;
            return Integer.compare(right.drawOrder, left.drawOrder);
        });
        return candidates.isEmpty() ? null : candidates.get(0).view;
    }

    private static int layoutWidth(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        return params == null ? view.getWidth() : params.width;
    }

    private static int layoutHeight(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        return params == null ? view.getHeight() : params.height;
    }

    private static List<View> rootViews(int maxRoots) {
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = type.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object global = getInstance.invoke(null);
            Field views = type.getDeclaredField("mViews");
            views.setAccessible(true);
            Object value = views.get(global);
            if (!(value instanceof List<?> list)) return List.of();
            List<View> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof View view) result.add(view);
                if (result.size() >= maxRoots) break;
            }
            return result;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static JSONArray bounds(View view) {
        JSONArray result = new JSONArray();
        Rect rect = new Rect();
        try {
            if (view.getGlobalVisibleRect(rect)) {
                result.put(rect.left).put(rect.top).put(rect.right).put(rect.bottom);
                return result;
            }
        } catch (Throwable ignored) {}
        result.put(0).put(0).put(0).put(0);
        return result;
    }

    private static Object resourceName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return JSONObject.NULL;
        try { return view.getResources().getResourceName(id); }
        catch (Throwable ignored) { return String.valueOf(id); }
    }

    private static String nodeId(View view) {
        return "v_" + Integer.toHexString(System.identityHashCode(view));
    }

    private static String safeString(Object value) {
        try { return truncate(String.valueOf(value)); }
        catch (Throwable ignored) { return "<toString failed>"; }
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() > MAX_STRING ? value.substring(0, MAX_STRING) : value;
    }

    private static final class NodeBudget {
        final int max;
        int used;
        boolean truncated;

        NodeBudget(int max) { this.max = max; }

        boolean hasRoom() {
            boolean room = used < max;
            if (!room) truncated = true;
            return room;
        }

        void take() {
            if (used < max) used++; else truncated = true;
        }
    }

    private static final class Candidate {
        final View view;
        final String parentId;
        final int indexInParent;
        final int depth;
        final int drawOrder;

        Candidate(View view, String parentId, int indexInParent, int depth, int drawOrder) {
            this.view = view;
            this.parentId = parentId;
            this.indexInParent = indexInParent;
            this.depth = depth;
            this.drawOrder = drawOrder;
        }
    }
}
