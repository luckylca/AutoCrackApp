package com.luckylca.simplehook.testapp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.lang.reflect.Method;
import org.json.JSONObject;

public final class SimpleHookTestProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        if (Binder.getCallingUid() != 0 && Binder.getCallingUid() != Process.SHELL_UID) {
            return result(error("ACCESS_DENIED", "Test controls require Android root or shell"));
        }
        if (!"invoke".equals(method)) {
            return result(error("UNKNOWN_METHOD", "Unknown test method: " + method));
        }
        try {
            return result(invoke(argument));
        } catch (Throwable error) {
            return result(error("TEST_FAILED", error.toString()));
        }
    }

    private JSONObject invoke(String operation) throws Exception {
        HookTargets target = new HookTargets("provider");
        JSONObject response = new JSONObject().put("ok", true).put("operation", operation);
        return switch (operation) {
            case "get_int" -> response.put("value", target.getInt());
            case "get_boolean" -> response.put("value", target.getBoolean());
            case "get_string" -> response.put("value", target.getString());
            case "add" -> response.put("value", target.add(2, 3));
            case "overload_int" -> response.put("value", target.overload(5));
            case "overload_string" -> response.put("value", target.overload("five"));
            case "constructor" -> response.put("value", target.constructorValue());
            case "fields" -> response.put("static_field", HookTargets.staticField)
                    .put("instance_field", target.instanceField);
            case "exception" -> invokeException(response, target);
            case "load_delayed" -> invokeDelayed(response);
            case "reset_fields" -> {
                HookTargets.staticField = 7;
                target.instanceField = 11;
                yield response.put("static_field", HookTargets.staticField)
                        .put("instance_field", target.instanceField);
            }
            default -> error("UNKNOWN_OPERATION", "Unknown test operation: " + operation);
        };
    }

    private static JSONObject invokeException(JSONObject response, HookTargets target) throws Exception {
        try {
            target.exceptionMethod();
            return response.put("threw", false);
        } catch (IllegalStateException error) {
            return response.put("threw", true).put("exception", error.toString());
        }
    }

    private static JSONObject invokeDelayed(JSONObject response) throws Exception {
        Class<?> type = Class.forName("com.luckylca.simplehook.testapp.DelayedTarget");
        Method method = type.getDeclaredMethod("loaded");
        return response.put("value", method.invoke(null));
    }

    private static JSONObject error(String code, String message) {
        try {
            return new JSONObject().put("ok", false)
                    .put("error", new JSONObject().put("code", code).put("message", message));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Bundle result(JSONObject value) {
        Bundle bundle = new Bundle();
        bundle.putString("json", value.toString());
        return bundle;
    }

    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
