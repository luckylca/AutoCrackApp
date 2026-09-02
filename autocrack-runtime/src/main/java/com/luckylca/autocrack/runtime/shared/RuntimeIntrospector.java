package com.luckylca.autocrack.runtime.shared;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reflection/object/activity/class capabilities shared by runtime-inspect and other toolpacks. */
public final class RuntimeIntrospector {
    private static final int MAX_CLASSLOADERS = 256;
    private static final int MAX_CLASSES = 10_000;
    private static final int MAX_MEMBERS = 2_000;
    private static final int MAX_FIELDS = 512;
    private static final int MAX_STRING = 2_048;
    private RuntimeIntrospector() {}

    public static boolean supports(String kind) {
        return Set.of(
                "runtime.process", "runtime.activities", "runtime.declared_activities",
                "runtime.classloaders", "runtime.class.search", "runtime.class.describe",
                "object.describe", "object.fields", "object.dump", "object.pin",
                "object.release", "object.clear_session").contains(kind);
    }

    public static JSONObject execute(Context context, JSONObject request) throws Exception {
        String kind = request.getString("kind");
        return switch (kind) {
            case "runtime.process" -> process(context);
            case "runtime.activities" -> activities();
            case "runtime.declared_activities" -> declaredActivities(context);
            case "runtime.classloaders" -> classLoaders();
            case "runtime.class.search" -> classSearch(request);
            case "runtime.class.describe" -> classDescribe(request);
            case "object.describe" -> objectDescribe(request);
            case "object.fields" -> objectFields(request);
            case "object.dump" -> objectDump(request);
            case "object.pin" -> objectPin(request);
            case "object.release" -> objectRelease(request);
            case "object.clear_session" -> objectClearSession(request);
            default -> error("UNSUPPORTED_KIND", kind);
        };
    }

    private static JSONObject process(Context context) throws Exception {
        ObjectRegistry registry = ObjectRegistry.get();
        return ok().put("package", context.getPackageName())
                .put("process", registry.processName())
                .put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("api_level", Build.VERSION.SDK_INT)
                .put("release", Build.VERSION.RELEASE)
                .put("data_dir", context.getApplicationInfo().dataDir)
                .put("source_dir", context.getApplicationInfo().sourceDir)
                .put("classloader_count", ClassLoaderRegistry.get().snapshot().size())
                .put("object_handle_count", registry.size());
    }

    private static JSONObject activities() throws Exception {
        JSONArray values = new JSONArray();
        IdentityHashMap<Activity, Boolean> seen = new IdentityHashMap<>();
        for (ActivityRegistry.ActivitySnapshot snapshot : ActivityRegistry.get().snapshot()) {
            Activity activity = snapshot.activity();
            if (activity == null || seen.containsKey(activity)) continue;
            seen.put(activity, Boolean.TRUE);
            values.put(activityJson(activity, snapshot.state(), "lifecycle_callback"));
        }
        int reflectedRecords = 0;
        JSONArray reflectionErrors = new JSONArray();
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            Object raw = readField(thread, "mActivities");
            if (raw instanceof Map<?, ?> records) {
                reflectedRecords = records.size();
                for (Object record : records.values()) {
                    Object value = readField(record, "activity");
                    if (!(value instanceof Activity activity) || seen.containsKey(activity)) continue;
                    seen.put(activity, Boolean.TRUE);
                    values.put(activityJson(activity, reflectedState(record), "ActivityThread.mActivities"));
                }
            }
        } catch (Throwable error) {
            reflectionErrors.put(error.toString());
        }
        return ok().put("count", values.length()).put("activities", values)
                .put("activity_thread_records", reflectedRecords)
                .put("activity_thread_errors", reflectionErrors);
    }

    private static JSONObject activityJson(Activity activity, String state, String source) throws Exception {
        JSONObject value = new JSONObject()
                .put("class", activity.getClass().getName())
                .put("handle", ObjectRegistry.get().put(activity, false, "runtime"))
                .put("state", state == null ? "unknown" : state)
                .put("source", source)
                .put("task_id", activity.getTaskId())
                .put("finishing", activity.isFinishing());
        if (Build.VERSION.SDK_INT >= 17) value.put("destroyed", activity.isDestroyed());
        Intent intent = activity.getIntent();
        if (intent != null) value.put("intent", intent(intent));
        return value;
    }

    private static String reflectedState(Object record) {
        try {
            Object lifecycle = readField(record, "mLifecycleState");
            if (lifecycle != null) return String.valueOf(lifecycle);
        } catch (Throwable ignored) {}
        try { if (Boolean.TRUE.equals(readField(record, "paused"))) return "paused"; } catch (Throwable ignored) {}
        try { if (Boolean.TRUE.equals(readField(record, "stopped"))) return "stopped"; } catch (Throwable ignored) {}
        return "reflected";
    }

    private static JSONObject declaredActivities(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_ACTIVITIES);
        JSONArray values = new JSONArray();
        if (info.activities != null) {
            int limit = Math.min(info.activities.length, MAX_MEMBERS);
            for (int i = 0; i < limit; i++) {
                ActivityInfo activity = info.activities[i];
                values.put(new JSONObject().put("class", activity.name)
                        .put("exported", activity.exported).put("enabled", activity.enabled)
                        .put("permission", activity.permission == null ? JSONObject.NULL : activity.permission)
                        .put("process", activity.processName == null ? JSONObject.NULL : activity.processName)
                        .put("launch_mode", activity.launchMode));
            }
        }
        return ok().put("count", values.length()).put("activities", values)
                .put("truncated", info.activities != null && info.activities.length > values.length());
    }

    private static JSONObject classLoaders() throws Exception {
        List<ClassLoader> loaders = ClassLoaderRegistry.get().snapshot();
        JSONArray values = new JSONArray();
        int limit = Math.min(MAX_CLASSLOADERS, loaders.size());
        for (int i = 0; i < limit; i++) {
            ClassLoader loader = loaders.get(i);
            JSONObject value = new JSONObject().put("index", i)
                    .put("handle", ObjectRegistry.get().put(loader, false, "classloader"))
                    .put("class", loader.getClass().getName()).put("text", cut(String.valueOf(loader)));
            ClassLoader parent = loader.getParent();
            if (parent != null) value.put("parent_handle", ObjectRegistry.get().put(parent, false, "classloader"));
            JSONArray dex = dexElements(loader, 256, false);
            value.put("dex", dex);
            values.put(value);
        }
        return ok().put("count", values.length()).put("classloaders", values)
                .put("truncated", loaders.size() > limit);
    }

    private static JSONObject classSearch(JSONObject request) throws Exception {
        String query = request.optString("query", "");
        if (query.length() > 512) return error("QUERY_TOO_LONG", "query exceeds 512 characters");
        String mode = request.optString("mode", "substring");
        int max = clamp(request.optInt("max_classes", 1000), 1, MAX_CLASSES);
        Pattern regex = "regex".equals(mode) ? Pattern.compile(query) : null;
        List<ClassLoader> loaders = selectedLoaders(request.optString("loader", null));
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        int scanned = 0;
        boolean scanTruncated = false;
        for (ClassLoader loader : loaders) {
            Enumeration<String> names = classNames(loader);
            if (names == null) continue;
            while (names.hasMoreElements()) {
                String name = names.nextElement(); scanned++;
                boolean match = switch (mode) {
                    case "exact" -> name.equals(query);
                    case "regex" -> regex.matcher(name).find();
                    default -> name.contains(query);
                };
                if (match) matches.add(name);
                if (matches.size() >= max || scanned >= MAX_CLASSES * 20) { scanTruncated = true; break; }
            }
            if (matches.size() >= max || scanTruncated) break;
        }
        JSONArray values = new JSONArray(); for (String item : matches) values.put(item);
        return ok().put("query", query).put("mode", mode).put("scanned", scanned)
                .put("count", values.length()).put("classes", values)
                .put("truncated", scanTruncated || matches.size() >= max);
    }

    private static JSONObject classDescribe(JSONObject request) throws Exception {
        String className = request.getString("class");
        Class<?> type = findClass(className, request.optString("loader", null), request.optBoolean("allow_load", true));
        if (type == null) return error("CLASS_NOT_FOUND", className);
        return describeClass(type, request.optInt("max_members", MAX_MEMBERS));
    }

    private static JSONObject describeClass(Class<?> type, int requestedMax) throws Exception {
        int max = clamp(requestedMax, 1, MAX_MEMBERS);
        JSONObject result = ok().put("class", type.getName()).put("modifiers", type.getModifiers())
                .put("modifier_text", Modifier.toString(type.getModifiers()))
                .put("interface", type.isInterface()).put("enum", type.isEnum()).put("annotation", type.isAnnotation())
                .put("loader_handle", type.getClassLoader() == null ? JSONObject.NULL : ObjectRegistry.get().put(type.getClassLoader(), false, "classloader"));
        Class<?> parent = type.getSuperclass(); result.put("superclass", parent == null ? JSONObject.NULL : parent.getName());
        JSONArray interfaces = new JSONArray(); for (Class<?> item : type.getInterfaces()) interfaces.put(item.getName()); result.put("interfaces", interfaces);
        JSONArray inner = new JSONArray(); for (Class<?> item : bounded(type.getDeclaredClasses(), max)) inner.put(item.getName()); result.put("inner_classes", inner);

        JSONArray fields = new JSONArray();
        for (Field field : bounded(type.getDeclaredFields(), max)) {
            fields.put(new JSONObject().put("name", field.getName()).put("type", field.getType().getName())
                    .put("static", Modifier.isStatic(field.getModifiers())).put("modifiers", Modifier.toString(field.getModifiers())));
        }
        result.put("fields", fields);

        JSONArray constructors = new JSONArray();
        for (Constructor<?> constructor : bounded(type.getDeclaredConstructors(), max)) {
            constructors.put(new JSONObject().put("class", type.getName()).put("name", "<init>")
                    .put("parameters", typeNames(constructor.getParameterTypes())).put("return_type", "void")
                    .put("constructor", true).put("static", false)
                    .put("modifiers", Modifier.toString(constructor.getModifiers())));
        }
        result.put("constructors", constructors);

        JSONArray methods = new JSONArray();
        for (Method method : bounded(type.getDeclaredMethods(), max)) {
            methods.put(new JSONObject().put("class", type.getName()).put("name", method.getName())
                    .put("parameters", typeNames(method.getParameterTypes()))
                    .put("return_type", method.getReturnType().getName())
                    .put("constructor", false).put("static", Modifier.isStatic(method.getModifiers()))
                    .put("modifiers", Modifier.toString(method.getModifiers())));
        }
        result.put("methods", methods)
                .put("truncated", type.getDeclaredMethods().length > methods.length()
                        || type.getDeclaredFields().length > fields.length()
                        || type.getDeclaredConstructors().length > constructors.length());
        return result;
    }

    private static JSONObject objectDescribe(JSONObject request) throws Exception {
        Object value = requireObject(request);
        String handle = request.getString("handle");
        Class<?> type = value.getClass();
        JSONObject result = ok().put("handle", handle).put("class", type.getName())
                .put("identity_hash", System.identityHashCode(value)).put("text", cut(safeToString(value)))
                .put("array", type.isArray()).put("collection", value instanceof Collection<?>)
                .put("map", value instanceof Map<?, ?>);
        ObjectRegistry.HandleInfo info = ObjectRegistry.get().info(handle);
        if (info != null) result.put("lifecycle", new JSONObject()
                .put("pinned", info.pinned())
                .put("session", info.session() == null ? JSONObject.NULL : info.session())
                .put("package", info.packageName())
                .put("process", info.processName())
                .put("pid", info.pid())
                .put("created_at", info.createdAt())
                .put("last_access_at", info.lastAccessAt())
                .put("expires_at", info.expiresAt()));
        return result;
    }

    private static JSONObject objectFields(JSONObject request) throws Exception {
        Object value = requireObject(request); int max = clamp(request.optInt("max_fields", 128), 1, MAX_FIELDS);
        JSONArray fields = new JSONArray(); int seen = 0;
        for (Class<?> type = value.getClass(); type != null && seen < max; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (seen++ >= max) break;
                JSONObject item = new JSONObject().put("declaring_class", type.getName()).put("name", field.getName())
                        .put("type", field.getType().getName()).put("static", Modifier.isStatic(field.getModifiers()))
                        .put("modifiers", Modifier.toString(field.getModifiers()));
                try {
                    field.setAccessible(true); Object fieldValue = field.get(Modifier.isStatic(field.getModifiers()) ? null : value);
                    item.put("value", summarize(fieldValue));
                    if (fieldValue != null && !isScalar(fieldValue.getClass())) item.put("handle", ObjectRegistry.get().put(fieldValue, false, "object"));
                } catch (Throwable error) { item.put("error", error.toString()); }
                fields.put(item);
            }
        }
        return ok().put("handle", request.getString("handle")).put("count", fields.length())
                .put("truncated", seen >= max).put("fields", fields);
    }

    private static JSONObject objectDump(JSONObject request) throws Exception {
        Object value = requireObject(request);
        DumpLimits limits = new DumpLimits(clamp(request.optInt("max_depth", 4), 0, 12),
                clamp(request.optInt("max_fields", 128), 1, MAX_FIELDS),
                clamp(request.optInt("max_array", 64), 1, 4096),
                clamp(request.optInt("max_string", MAX_STRING), 16, 65536));
        IdentityHashMap<Object, String> seen = new IdentityHashMap<>();
        return ok().put("handle", request.getString("handle")).put("value", dump(value, 0, limits, seen));
    }

    private static JSONObject objectPin(JSONObject request) throws Exception {
        boolean pin = request.optBoolean("pin", true); boolean changed = ObjectRegistry.get().pin(request.getString("handle"), pin);
        return changed ? ok().put("handle", request.getString("handle")).put("pinned", pin) : error("STALE_HANDLE", request.getString("handle"));
    }
    private static JSONObject objectRelease(JSONObject request) throws Exception {
        return ok().put("handle", request.getString("handle")).put("released", ObjectRegistry.get().release(request.getString("handle")));
    }
    private static JSONObject objectClearSession(JSONObject request) throws Exception {
        return ok().put("session", request.getString("session")).put("released", ObjectRegistry.get().clearSession(request.getString("session")));
    }

    private static Object dump(Object value, int depth, DumpLimits limits, IdentityHashMap<Object,String> seen) throws Exception {
        if (value == null) return JSONObject.NULL;
        Class<?> type = value.getClass();
        if (isScalar(type)) return scalar(value, limits.maxString);
        String existing = seen.get(value); if (existing != null) return new JSONObject().put("$ref", existing);
        String handle = ObjectRegistry.get().put(value, false, "dump"); seen.put(value, handle);
        JSONObject out = new JSONObject().put("$handle", handle).put("$class", type.getName());
        if (depth >= limits.maxDepth) return out.put("$truncated", "max_depth");
        if (type.isArray()) {
            int length = Array.getLength(value), n = Math.min(length, limits.maxArray); JSONArray items = new JSONArray();
            for (int i=0;i<n;i++) items.put(dump(Array.get(value,i), depth+1, limits, seen));
            return out.put("length", length).put("items", items).put("truncated", length > n);
        }
        if (value instanceof Collection<?> collection) {
            JSONArray items = new JSONArray(); int i=0; for (Object item : collection) { if (i++ >= limits.maxArray) break; items.put(dump(item, depth+1, limits, seen)); }
            return out.put("size", collection.size()).put("items", items).put("truncated", collection.size() > items.length());
        }
        if (value instanceof Map<?,?> map) {
            JSONArray items = new JSONArray(); int i=0; for (Map.Entry<?,?> entry : map.entrySet()) { if (i++ >= limits.maxArray) break; items.put(new JSONObject().put("key", dump(entry.getKey(),depth+1,limits,seen)).put("value",dump(entry.getValue(),depth+1,limits,seen))); }
            return out.put("size", map.size()).put("entries", items).put("truncated", map.size() > items.length());
        }
        JSONObject fields = new JSONObject(); int count=0;
        for (Class<?> cursor=type; cursor!=null && count<limits.maxFields; cursor=cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                if (count++ >= limits.maxFields) break;
                String name = cursor.getName() + "#" + field.getName();
                try { field.setAccessible(true); fields.put(name, dump(field.get(value), depth+1, limits, seen)); }
                catch (Throwable error) { fields.put(name, new JSONObject().put("$error", error.toString())); }
            }
        }
        return out.put("fields", fields).put("truncated", count >= limits.maxFields);
    }

    static JSONArray dexElements(ClassLoader loader, int max, boolean includeClassCount) throws Exception {
        JSONArray values = new JSONArray(); Object pathList = readField(loader, "pathList"); if (pathList == null) return values;
        Object elements = readField(pathList, "dexElements"); if (elements == null || !elements.getClass().isArray()) return values;
        int length = Math.min(Array.getLength(elements), max);
        for (int i=0;i<length;i++) {
            Object element = Array.get(elements,i); Object dexFile = readField(element, "dexFile");
            JSONObject item = new JSONObject().put("index", i).put("element_class", element.getClass().getName());
            if (dexFile != null) {
                item.put("dex_handle", ObjectRegistry.get().put(dexFile,false,"dex"));
                try { Method name = dexFile.getClass().getMethod("getName"); item.put("name", String.valueOf(name.invoke(dexFile))); } catch (Throwable ignored) {}
                if (includeClassCount) { Enumeration<String> entries = entries(dexFile); int c=0; if (entries!=null) while(entries.hasMoreElements() && c<MAX_CLASSES){entries.nextElement();c++;} item.put("class_count_at_least",c); }
            }
            values.put(item);
        }
        return values;
    }

    static Enumeration<String> classNames(ClassLoader loader) {
        try {
            Object pathList=readField(loader,"pathList"); Object elements=readField(pathList,"dexElements");
            if (elements==null || !elements.getClass().isArray()) return null;
            List<Enumeration<String>> all=new ArrayList<>();
            for(int i=0;i<Array.getLength(elements);i++){Object dex=readField(Array.get(elements,i),"dexFile"); Enumeration<String> e=entries(dex); if(e!=null) all.add(e);}
            return new Enumeration<>() { int index; public boolean hasMoreElements(){while(index<all.size()&&!all.get(index).hasMoreElements())index++;return index<all.size();} public String nextElement(){hasMoreElements();return all.get(index).nextElement();} };
        } catch(Throwable ignored){return null;}
    }

    private static Enumeration<String> entries(Object dexFile) {
        if (dexFile==null) return null;
        try { Method entries=dexFile.getClass().getMethod("entries"); @SuppressWarnings("unchecked") Enumeration<String> value=(Enumeration<String>)entries.invoke(dexFile); return value; }
        catch(Throwable ignored){return null;}
    }

    private static List<ClassLoader> selectedLoaders(String handle) throws Exception {
        if (handle != null && !handle.isBlank()) {
            Object value = ObjectRegistry.get().get(handle); if (!(value instanceof ClassLoader loader)) throw new IllegalArgumentException("loader handle is stale or not ClassLoader");
            return List.of(loader);
        }
        return ClassLoaderRegistry.get().snapshot();
    }

    private static Class<?> findClass(String name, String loaderHandle, boolean allowLoad) throws Exception {
        Method findLoaded = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class); findLoaded.setAccessible(true);
        for (ClassLoader loader : selectedLoaders(loaderHandle)) {
            try { Object loaded=findLoaded.invoke(loader,name); if(loaded instanceof Class<?> type)return type; } catch(Throwable ignored){}
            if (allowLoad) try { return Class.forName(name,false,loader); } catch(Throwable ignored){}
        }
        return null;
    }

    private static Object requireObject(JSONObject request) {
        String handle=request.optString("handle","");
        String session=request.has("session")&&!request.isNull("session")?request.optString("session",null):null;
        Object value=ObjectRegistry.get().get(handle,session);
        if(value==null) throw new IllegalArgumentException("STALE_HANDLE:"+handle); return value;
    }
    private static Object readField(Object object, String name) throws Exception {
        if(object==null)return null; Class<?> type=object.getClass();
        while(type!=null){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException e){type=type.getSuperclass();}}
        return null;
    }
    private static JSONObject intent(Intent intent) throws Exception {
        JSONObject value=new JSONObject().put("action",intent.getAction()==null?JSONObject.NULL:intent.getAction())
                .put("data",intent.getDataString()==null?JSONObject.NULL:intent.getDataString())
                .put("flags",intent.getFlags());
        if(intent.getComponent()!=null)value.put("component",intent.getComponent().flattenToShortString());
        if(intent.getCategories()!=null)value.put("categories",new JSONArray(intent.getCategories())); return value;
    }
    private static JSONArray typeNames(Class<?>[] types){JSONArray out=new JSONArray();for(Class<?> type:types)out.put(type.getName());return out;}
    private static <T> List<T> bounded(T[] values,int max){List<T> out=new ArrayList<>();for(int i=0;i<values.length&&i<max;i++)out.add(values[i]);return out;}
    private static Object summarize(Object value) throws Exception {
        if(value==null)return JSONObject.NULL; if(isScalar(value.getClass()))return scalar(value,MAX_STRING);
        if(value.getClass().isArray())return new JSONObject().put("class",value.getClass().getName()).put("length",Array.getLength(value));
        if(value instanceof Collection<?> c)return new JSONObject().put("class",value.getClass().getName()).put("size",c.size());
        if(value instanceof Map<?,?> m)return new JSONObject().put("class",value.getClass().getName()).put("size",m.size());
        return new JSONObject().put("class",value.getClass().getName()).put("text",cut(safeToString(value)));
    }
    private static boolean isScalar(Class<?> type){return type.isPrimitive()||Number.class.isAssignableFrom(type)||Boolean.class==type||Character.class==type||String.class==type||CharSequence.class.isAssignableFrom(type)||Enum.class.isAssignableFrom(type)||Class.class==type;}
    private static Object scalar(Object value,int max){if(value instanceof Character||value instanceof CharSequence||value instanceof Enum<?>||value instanceof Class<?>)return cut(String.valueOf(value),max);return value;}
    private static String safeToString(Object value){try{return String.valueOf(value);}catch(Throwable e){return "<toString failed: "+e+">";}}
    private static String cut(String value){return cut(value,MAX_STRING);} private static String cut(String value,int max){return value!=null&&value.length()>max?value.substring(0,max):value;}
    private static int clamp(int value,int min,int max){return Math.max(min,Math.min(max,value));}
    private static JSONObject ok() throws Exception{return new JSONObject().put("ok",true);} private static JSONObject error(String code,String message)throws Exception{return new JSONObject().put("ok",false).put("error",new JSONObject().put("code",code).put("message",message));}
    private record DumpLimits(int maxDepth,int maxFields,int maxArray,int maxString){}
}
