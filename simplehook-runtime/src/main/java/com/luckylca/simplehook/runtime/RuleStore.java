package com.luckylca.simplehook.runtime;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import com.luckylca.simplehook.core.HookRule;
import com.luckylca.simplehook.core.RuleState;
import com.luckylca.simplehook.core.SimpleHookLimits;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

@SuppressLint({"ApplySharedPref", "WorldReadableFiles"})
final class RuleStore {
    private static final String PREFS = "simplehook_rules";
    private static final String RULES = "rules";
    private static final String GENERATION = "generation";
    private static final String STATES = "states";
    private static final String STATE_ORDERS = "state_orders";
    private static final String INSPECTION_REQUESTS = "inspection_requests";
    private static final String INSPECTION_RESULTS = "inspection_results";
    private static final String WORLD_READABLE_MIGRATED = "world_readable_migrated_v1";
    static final String CHANNEL_TOKEN = "channel_token";
    private final SharedPreferences preferences;

    RuleStore(Context context) {
        Context credentialContext = context;
        Context deviceContext = context.createDeviceProtectedStorageContext();
        // LSPosed API 93 redirects this file into its protected preference bridge. The legacy
        // world-readable mode is the signal that permits XSharedPreferences readers in scoped apps.
        SharedPreferences credentialPreferences = credentialContext.getSharedPreferences(PREFS, Context.MODE_WORLD_READABLE);
        SharedPreferences devicePreferences = deviceContext.getSharedPreferences(PREFS, Context.MODE_WORLD_READABLE);
        if (!credentialPreferences.contains(RULES) && devicePreferences.contains(RULES)) {
            credentialContext.moveSharedPreferencesFrom(deviceContext, PREFS);
            credentialPreferences = credentialContext.getSharedPreferences(PREFS, Context.MODE_WORLD_READABLE);
        }
        if (!credentialPreferences.getBoolean(WORLD_READABLE_MIGRATED, false)
                || !credentialPreferences.contains(CHANNEL_TOKEN)) {
            SharedPreferences.Editor migration = credentialPreferences.edit()
                    .putBoolean(WORLD_READABLE_MIGRATED, true);
            if (!credentialPreferences.contains(CHANNEL_TOKEN)) {
                migration.putString(CHANNEL_TOKEN, UUID.randomUUID().toString());
            }
            migration.commit();
        }
        preferences = credentialPreferences;
    }

    String channelToken() {
        return preferences.getString(CHANNEL_TOKEN, "");
    }

    synchronized JSONArray rules() throws JSONException {
        return new JSONArray(preferences.getString(RULES, "[]"));
    }

    synchronized JSONArray rulesForPackage(String packageName, String processName) throws JSONException {
        JSONArray result = new JSONArray();
        JSONArray all = rules();
        for (int i = 0; i < all.length(); i++) {
            JSONObject rule = all.getJSONObject(i);
            if (!packageName.equals(rule.optString("package"))) continue;
            if (!rule.optBoolean("enabled", true)) continue;
            String wantedProcess = rule.isNull("process") ? null : rule.optString("process", null);
            if (wantedProcess != null && !wantedProcess.equals(processName)) continue;
            result.put(rule);
        }
        return result;
    }

    synchronized JSONObject find(String id) throws JSONException {
        JSONArray all = rules();
        for (int i = 0; i < all.length(); i++) {
            JSONObject rule = all.getJSONObject(i);
            if (id.equals(rule.optString("id"))) return rule;
        }
        return null;
    }

    synchronized boolean upsert(JSONObject input, boolean createOnly) throws JSONException {
        HookRule.parse(input);
        JSONArray all = rules();
        int existing = -1;
        for (int i = 0; i < all.length(); i++) {
            if (input.getString("id").equals(all.getJSONObject(i).getString("id"))) existing = i;
        }
        if (createOnly && existing >= 0) throw new IllegalArgumentException("Rule already exists");
        if (existing < 0 && all.length() >= SimpleHookLimits.MAX_RULES) {
            throw new IllegalArgumentException("Maximum rule count exceeded");
        }
        if (existing >= 0) all.put(existing, input); else all.put(input);
        save(all);
        setState(input.getString("id"), input.optBoolean("enabled", true) ? RuleState.WAITING_FOR_PROCESS : RuleState.DISABLED, null);
        return existing >= 0;
    }

    synchronized boolean remove(String id) throws JSONException {
        JSONArray all = rules();
        JSONArray kept = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < all.length(); i++) {
            JSONObject rule = all.getJSONObject(i);
            if (id.equals(rule.getString("id"))) removed = true; else kept.put(rule);
        }
        if (removed) save(kept);
        return removed;
    }

    synchronized boolean setEnabled(String id, boolean enabled) throws JSONException {
        JSONObject rule = find(id);
        if (rule == null) return false;
        rule.put("enabled", enabled);
        return upsert(rule, false) || true;
    }

    synchronized long generation() {
        return preferences.getLong(GENERATION, 0L);
    }

    synchronized long reload() {
        long next = generation() + 1L;
        preferences.edit().putLong(GENERATION, next).commit();
        return next;
    }

    synchronized void setState(String id, RuleState state, String detail) throws JSONException {
        JSONObject states = new JSONObject(preferences.getString(STATES, "{}"));
        JSONObject orders = objectPreference(STATE_ORDERS);
        states.put(id, stateValue(state, detail));
        orders.remove(id);
        preferences.edit()
                .putString(STATES, states.toString())
                .putString(STATE_ORDERS, orders.toString())
                .commit();
    }

    synchronized boolean setRuntimeState(String id, RuleState state, String detail, long eventOrder,
            long eventGeneration) throws JSONException {
        if (eventGeneration > 0L && eventGeneration != generation()) return false;
        if (eventOrder <= 0L) {
            setState(id, state, detail);
            return true;
        }
        JSONObject orders = objectPreference(STATE_ORDERS);
        if (eventOrder <= orders.optLong(id, -1L)) return false;
        JSONObject states = new JSONObject(preferences.getString(STATES, "{}"));
        states.put(id, stateValue(state, detail));
        orders.put(id, eventOrder);
        preferences.edit()
                .putString(STATES, states.toString())
                .putString(STATE_ORDERS, orders.toString())
                .commit();
        return true;
    }

    synchronized JSONObject state(String id) throws JSONException {
        return new JSONObject(preferences.getString(STATES, "{}")).optJSONObject(id);
    }

    synchronized JSONArray listWithState() throws JSONException {
        JSONArray result = new JSONArray();
        JSONArray all = rules();
        for (int i = 0; i < all.length(); i++) {
            JSONObject copy = new JSONObject(all.getJSONObject(i).toString());
            JSONObject state = state(copy.getString("id"));
            if (state != null) copy.put("runtime", state);
            result.put(copy);
        }
        return result;
    }

    synchronized String submitInspection(JSONObject request) throws JSONException {
        String id = UUID.randomUUID().toString();
        JSONObject requests = objectPreference(INSPECTION_REQUESTS);
        requests.put(id, new JSONObject(request.toString()).put("request_id", id));
        preferences.edit().putString(INSPECTION_REQUESTS, requests.toString()).commit();
        return id;
    }

    synchronized JSONArray pendingInspections(String packageName) throws JSONException {
        JSONArray pending = new JSONArray();
        JSONObject requests = objectPreference(INSPECTION_REQUESTS);
        java.util.Iterator<String> keys = requests.keys();
        while (keys.hasNext()) {
            JSONObject request = requests.getJSONObject(keys.next());
            if (packageName.equals(request.optString("package"))) pending.put(request);
        }
        return pending;
    }

    synchronized void completeInspection(String id, JSONObject result) throws JSONException {
        JSONObject requests = objectPreference(INSPECTION_REQUESTS);
        requests.remove(id);
        JSONObject results = objectPreference(INSPECTION_RESULTS);
        results.put(id, result);
        preferences.edit()
                .putString(INSPECTION_REQUESTS, requests.toString())
                .putString(INSPECTION_RESULTS, results.toString())
                .commit();
    }

    synchronized JSONObject consumeInspectionResult(String id) throws JSONException {
        JSONObject results = objectPreference(INSPECTION_RESULTS);
        JSONObject result = results.optJSONObject(id);
        if (result != null) {
            results.remove(id);
            preferences.edit().putString(INSPECTION_RESULTS, results.toString()).commit();
        }
        return result;
    }

    synchronized JSONObject inspectionRequest(String id) throws JSONException {
        return objectPreference(INSPECTION_REQUESTS).optJSONObject(id);
    }

    private JSONObject objectPreference(String key) throws JSONException {
        return new JSONObject(preferences.getString(key, "{}"));
    }

    private static JSONObject stateValue(RuleState state, String detail) throws JSONException {
        JSONObject value = new JSONObject()
                .put("state", state.name())
                .put("updated_at", System.currentTimeMillis());
        if (detail != null) value.put("detail", detail);
        return value;
    }

    private void save(JSONArray rules) {
        long next = generation() + 1L;
        preferences.edit().putString(RULES, rules.toString()).putLong(GENERATION, next).commit();
    }
}
