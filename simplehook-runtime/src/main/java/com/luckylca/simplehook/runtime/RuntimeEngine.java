package com.luckylca.simplehook.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import com.luckylca.simplehook.core.ConditionEvaluator;
import com.luckylca.simplehook.core.HookRule;
import com.luckylca.simplehook.core.RuleState;
import com.luckylca.simplehook.core.SimpleHookLimits;
import com.luckylca.simplehook.core.ValueCodec;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class RuntimeEngine {
    private final RuntimeChannel channel;
    private final String packageName;
    private final String processName;
    private final ClassLoader initialClassLoader;
    private final Set<ClassLoader> classLoaders = ConcurrentHashMap.newKeySet();
    private final Map<String, HookRule> activeRules = new ConcurrentHashMap<>();
    private final Set<String> installed = ConcurrentHashMap.newKeySet();
    private final Set<String> waitingForClass = ConcurrentHashMap.newKeySet();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger hookedMembers = new AtomicInteger();
    private final AtomicLong rateSecond = new AtomicLong();
    private final AtomicInteger rateCount = new AtomicInteger();
    private final AtomicLong stateOrder = new AtomicLong(System.currentTimeMillis() * 1_000L);
    private volatile long generation = -1L;

    RuntimeEngine(Context context, String packageName, String processName, ClassLoader classLoader) {
        this.channel = new RuntimeChannel(context);
        this.packageName = packageName;
        this.processName = processName;
        this.initialClassLoader = classLoader;
        this.classLoaders.add(classLoader);
    }

    void start() throws Throwable {
        installClassLoaderObserver();
        synchronizeRules();
        handler.postDelayed(this::poll, 1000L);
    }

    private void poll() {
        try {
            synchronizeRules();
            fulfillInspections();
        } catch (Throwable error) {
            XposedBridge.log("SimpleHook poll failed: " + error);
        } finally {
            handler.postDelayed(this::poll, 1000L);
        }
    }

    private void synchronizeRules() throws Exception {
        JSONObject response = channel.rulesForPackage(packageName, processName);
        if (!response.optBoolean("ok")) return;
        long nextGeneration = response.optLong("generation", 0L);
        if (nextGeneration == generation) {
            retryWaitingRules();
            heartbeat();
            return;
        }
        Map<String, HookRule> next = new LinkedHashMap<>();
        JSONArray rules = response.getJSONArray("rules");
        for (int i = 0; i < rules.length(); i++) {
            HookRule rule = HookRule.parse(rules.getJSONObject(i));
            next.put(rule.id, rule);
        }
        activeRules.clear();
        activeRules.putAll(next);
        waitingForClass.retainAll(next.keySet());
        generation = nextGeneration;
        for (HookRule rule : next.values()) install(rule);
        heartbeat();
    }

    private void heartbeat() throws JSONException {
        channel.send("heartbeat", new JSONObject()
                .put("package", packageName)
                .put("process", processName)
                .put("pid", Process.myPid())
                .put("generation", generation));
    }

    private void install(HookRule rule) {
        boolean loaded = false;
        Throwable lastError = null;
        for (ClassLoader loader : classLoaders) {
            try {
                Class<?> target = findAlreadyLoadedClass(loader, rule.target.className);
                if (target == null) throw new ClassNotFoundException(rule.target.className);
                installOnClass(rule, target);
                waitingForClass.remove(rule.id);
                loaded = true;
                break;
            } catch (ClassNotFoundException error) {
                lastError = error;
            } catch (Throwable error) {
                lastError = error;
                break;
            }
        }
        if (!loaded) {
            boolean waiting = lastError instanceof ClassNotFoundException;
            if (waiting) waitingForClass.add(rule.id); else waitingForClass.remove(rule.id);
            state(rule.id, waiting ? RuleState.WAITING_FOR_CLASS : RuleState.FAILED,
                    lastError == null ? null : lastError.toString());
        }
    }

    private void retryWaitingRules() {
        for (String id : waitingForClass.toArray(new String[0])) {
            HookRule rule = activeRules.get(id);
            if (rule == null) waitingForClass.remove(id); else install(rule);
        }
    }

    private void installOnClass(HookRule rule, Class<?> target) throws Throwable {
        if (rule.action.type.startsWith("field_")) {
            installFieldRule(rule, target);
            return;
        }
        List<Member> members = resolveMembers(rule, target);
        if (members.isEmpty()) throw new NoSuchMethodException(signature(rule));
        if (members.size() > SimpleHookLimits.MAX_WILDCARD_EXPANSION) {
            throw new IllegalArgumentException("Wildcard expands past " + SimpleHookLimits.MAX_WILDCARD_EXPANSION);
        }
        for (Member member : members) {
            if (member instanceof Method method) {
                Class<?> declaredReturn = ValueCodec.resolveType(rule.target.returnType, target.getClassLoader());
                if (method.getReturnType() != declaredReturn) {
                    throw new NoSuchMethodException("Return type mismatch for " + signature(rule));
                }
            }
            hookMember(rule, member);
        }
        state(rule.id, RuleState.ACTIVE, members.size() + " member(s)");
    }

    private List<Member> resolveMembers(HookRule rule, Class<?> target) throws Exception {
        Class<?>[] parameters = new Class<?>[rule.target.parameters.size()];
        for (int i = 0; i < parameters.length; i++) {
            parameters[i] = ValueCodec.resolveType(rule.target.parameters.get(i), target.getClassLoader());
        }
        if (rule.target.constructor) {
            return List.of(target.getDeclaredConstructor(parameters));
        }
        if (!rule.target.method.endsWith("*")) {
            return List.of(findMethod(target, rule.target.method, parameters));
        }
        String prefix = rule.target.method.substring(0, rule.target.method.length() - 1);
        List<Member> result = new ArrayList<>();
        for (Method method : target.getDeclaredMethods()) {
            if (method.getName().startsWith(prefix)
                    && Arrays.equals(method.getParameterTypes(), parameters)) result.add(method);
        }
        return result;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] parameters) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name + Arrays.toString(parameters));
    }

    private void hookMember(HookRule initialRule, Member member) {
        String key = initialRule.id + "|" + member.toString();
        if (!installed.add(key)) return;
        if (hookedMembers.incrementAndGet() > SimpleHookLimits.MAX_HOOKED_METHODS) {
            hookedMembers.decrementAndGet();
            state(initialRule.id, RuleState.FAILED, "Maximum hooked method count exceeded");
            return;
        }
        if (member instanceof java.lang.reflect.AccessibleObject accessible) accessible.setAccessible(true);
        XposedBridge.hookMethod(member, new XC_MethodHook() {
            private final ThreadLocal<Long> started = new ThreadLocal<>();
            private final ThreadLocal<Boolean> matchedBefore = new ThreadLocal<>();

            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                HookRule rule = activeRules.get(initialRule.id);
                if (rule == null || !rule.enabled || !sameTarget(rule, initialRule)) return;
                started.set(System.nanoTime());
                boolean matches = rule.condition == null
                        || (!"return_value".equals(rule.condition.source) && conditionMatches(rule, param, null));
                matchedBefore.set(matches);
                if (!matches) return;
                if ("replace_argument".equals(rule.action.type)) {
                    int index = rule.action.argumentIndex;
                    param.args[index] = ValueCodec.coerce(rule.action.value, rule.target.parameters.get(index));
                } else if ("skip_original".equals(rule.action.type)) {
                    param.setResult(ValueCodec.coerce(rule.action.value, rule.target.returnType));
                }
                if ("before".equals(rule.action.type) || "record".equals(rule.action.type)
                        || "replace_argument".equals(rule.action.type) || "skip_original".equals(rule.action.type)) {
                    log(rule, param, "before", null, null);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                HookRule rule = activeRules.get(initialRule.id);
                if (rule == null || !rule.enabled || !sameTarget(rule, initialRule)) return;
                long elapsed = started.get() == null ? 0L : (System.nanoTime() - started.get()) / 1000L;
                started.remove();
                Object originalResult = param.hasThrowable() ? null : param.getResult();
                boolean matches = rule.condition != null && "return_value".equals(rule.condition.source)
                        ? conditionMatches(rule, param, originalResult)
                        : Boolean.TRUE.equals(matchedBefore.get());
                matchedBefore.remove();
                if (matches && "replace_return".equals(rule.action.type)) {
                    param.setResult(ValueCodec.coerce(rule.action.value, rule.target.returnType));
                }
                if (matches && !"before".equals(rule.action.type)) log(rule, param, "after", elapsed, originalResult);
            }
        });
        state(initialRule.id, RuleState.INSTALLED, member.toString());
    }

    private void installFieldRule(HookRule rule, Class<?> target) throws Throwable {
        Field field = findField(target, rule.target.field);
        field.setAccessible(true);
        if (Modifier.isStatic(field.getModifiers())) {
            applyField(rule, field, null);
            state(rule.id, RuleState.ACTIVE, "static field");
            return;
        }
        Constructor<?>[] constructors = target.getDeclaredConstructors();
        if (constructors.length == 0) throw new NoSuchMethodException("No constructor available for instance field");
        for (Constructor<?> constructor : constructors) {
            String key = rule.id + "|field|" + constructor;
            if (!installed.add(key)) continue;
            if (hookedMembers.incrementAndGet() > SimpleHookLimits.MAX_HOOKED_METHODS) {
                hookedMembers.decrementAndGet();
                installed.remove(key);
                state(rule.id, RuleState.FAILED, "Maximum hooked method count exceeded");
                return;
            }
            XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    HookRule current = activeRules.get(rule.id);
                    if (current != null && current.enabled && sameTarget(current, rule)) {
                        applyField(current, field, param.thisObject);
                    }
                }
            });
        }
        state(rule.id, RuleState.ACTIVE, "instance field via " + constructors.length + " constructor(s)");
    }

    private void applyField(HookRule rule, Field field, Object receiver) throws IllegalAccessException, JSONException {
        Object before = field.get(receiver);
        if (rule.condition != null
                && !ConditionEvaluator.matches(rule.condition.operator, before, rule.condition.value)) return;
        if ("field_write".equals(rule.action.type)) {
            field.set(receiver, ValueCodec.coerce(rule.action.value, field.getType().getName()));
        }
        JSONObject entry = baseLog(rule, "field")
                .put("field", field.getName())
                .put("static", Modifier.isStatic(field.getModifiers()))
                .put("value_before", safeValue(before));
        if ("field_write".equals(rule.action.type)) entry.put("value_after", safeValue(field.get(receiver)));
        emit(entry);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        throw new NoSuchFieldException(type.getName() + "#" + name);
    }

    private boolean conditionMatches(HookRule rule, XC_MethodHook.MethodHookParam param, Object result) {
        if (rule.condition == null) return true;
        Object actual = switch (rule.condition.source) {
            case "argument" -> param.args[rule.condition.index];
            case "return_value" -> result;
            default -> null;
        };
        return ConditionEvaluator.matches(rule.condition.operator, actual, rule.condition.value);
    }

    private void log(HookRule rule, XC_MethodHook.MethodHookParam param, String phase, Long elapsed, Object originalResult) throws JSONException {
        if (!rule.logging.enabled) return;
        JSONObject entry = baseLog(rule, phase)
                .put("class", rule.target.className)
                .put("method", rule.target.constructor ? "<init>" : rule.target.method)
                .put("thread", Thread.currentThread().getName());
        if (rule.logging.arguments) entry.put("arguments", values(param.args));
        if ("after".equals(phase)) {
            if (param.hasThrowable()) {
                Throwable throwable = param.getThrowable();
                entry.put("exception", throwable.toString());
                if (rule.logging.stackTrace) entry.put("stack_trace", truncatedStack(throwable));
            } else if (rule.logging.returnValue) {
                entry.put("return_value", safeValue(param.getResult()));
                if (originalResult != param.getResult()) entry.put("original_return_value", safeValue(originalResult));
            }
            if (elapsed != null) entry.put("elapsed_us", elapsed);
        }
        emit(entry);
    }

    private JSONObject baseLog(HookRule rule, String phase) throws JSONException {
        return new JSONObject().put("timestamp", System.currentTimeMillis())
                .put("package", packageName).put("process", processName)
                .put("pid", Process.myPid()).put("tid", Process.myTid())
                .put("rule_id", rule.id).put("phase", phase);
    }

    private void emit(JSONObject entry) {
        long second = System.currentTimeMillis() / 1000L;
        if (rateSecond.getAndSet(second) != second) rateCount.set(0);
        if (rateCount.incrementAndGet() > SimpleHookLimits.MAX_LOGS_PER_SECOND) return;
        try {
            channel.send("log", new JSONObject().put("package", packageName).put("entry", entry));
        } catch (Throwable error) {
            XposedBridge.log("SimpleHook log delivery failed: " + error);
        }
    }

    private static JSONArray values(Object[] values) {
        JSONArray result = new JSONArray();
        if (values != null) for (Object value : values) result.put(safeValue(value));
        return result;
    }

    private static Object safeValue(Object value) {
        if (value == null) return JSONObject.NULL;
        if (value instanceof Boolean || value instanceof Number || value instanceof String) return value;
        if (value instanceof Character) return String.valueOf(value);
        if (value.getClass().isArray()) return value.getClass().getComponentType().getName() + "[" + java.lang.reflect.Array.getLength(value) + "]";
        String text;
        try { text = String.valueOf(value); } catch (Throwable ignored) { text = "<toString failed>"; }
        return text.length() > 2048 ? text.substring(0, 2048) : text;
    }

    private static String truncatedStack(Throwable throwable) {
        StringBuilder result = new StringBuilder();
        for (StackTraceElement element : throwable.getStackTrace()) {
            String line = "\tat " + element + "\n";
            if (result.length() + line.length() > SimpleHookLimits.MAX_STACK_TRACE_CHARS) break;
            result.append(line);
        }
        return result.toString();
    }

    private void installClassLoaderObserver() throws Throwable {
        observeClassLoaderMethod(ClassLoader.class.getDeclaredMethod("loadClass", String.class));
        observeClassLoaderMethod(ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class));
        Class<?> baseDexClassLoader = Class.forName("dalvik.system.BaseDexClassLoader");
        observeClassLoaderMethod(baseDexClassLoader.getDeclaredMethod("findClass", String.class));
        for (Constructor<?> constructor : baseDexClassLoader.getDeclaredConstructors()) {
            XposedBridge.hookMethod(constructor, new XC_MethodHook(10) {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ClassLoader loader) classLoaders.add(loader);
                }
            });
        }
    }

    private void observeClassLoaderMethod(Method method) {
        XposedBridge.hookMethod(method, new XC_MethodHook(10) {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || !(param.getResult() instanceof Class<?> loaded)) return;
                classLoaders.add(loaded.getClassLoader() == null ? initialClassLoader : loaded.getClassLoader());
                for (HookRule rule : activeRules.values()) {
                    if (!rule.target.className.equals(loaded.getName())) continue;
                    try {
                        installOnClass(rule, loaded);
                        waitingForClass.remove(rule.id);
                    } catch (Throwable error) {
                        state(rule.id, RuleState.FAILED, error.toString());
                    }
                }
            }
        });
    }

    private void fulfillInspections() throws JSONException {
        JSONArray requests = channel.pendingInspections(packageName);
        for (int i = 0; i < requests.length(); i++) {
            JSONObject request = requests.getJSONObject(i);
            JSONObject result;
            try {
                String kind = request.getString("kind");
                if (RuntimeInspectorPrimitives.supports(kind)) {
                    result = RuntimeInspectorPrimitives.executeRequest(request);
                } else {
                    Class<?> type = findLoadedClass(request.getString("class"));
                    result = inspect(type, kind);
                }
            } catch (ClassNotFoundException error) {
                result = new JSONObject().put("ok", false).put("error", new JSONObject()
                        .put("code", "CLASS_NOT_FOUND").put("message", "Target class is not currently available"));
            } catch (Throwable error) {
                result = new JSONObject().put("ok", false).put("error", new JSONObject()
                        .put("code", "INSPECT_FAILED").put("message", error.toString()));
            }
            channel.send("inspect_complete", new JSONObject()
                    .put("request_id", request.getString("request_id"))
                    .put("package", packageName)
                    .put("result", result));
        }
    }

    private Class<?> findLoadedClass(String name) throws ClassNotFoundException {
        for (ClassLoader loader : classLoaders) {
            try {
                Class<?> loaded = findAlreadyLoadedClass(loader, name);
                if (loaded != null) return loaded;
            } catch (ReflectiveOperationException ignored) {}
        }
        throw new ClassNotFoundException(name);
    }

    private static Class<?> findAlreadyLoadedClass(ClassLoader loader, String name)
            throws ReflectiveOperationException {
        Method method = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
        method.setAccessible(true);
        try {
            return (Class<?>) method.invoke(loader, name);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ReflectiveOperationException reflective) throw reflective;
            throw error;
        }
    }

    private static JSONObject inspect(Class<?> type, String kind) throws JSONException {
        JSONObject result = new JSONObject().put("ok", true).put("class", type.getName());
        if ("class".equals(kind)) {
            result.put("superclass", type.getSuperclass() == null ? JSONObject.NULL : type.getSuperclass().getName())
                    .put("interface", type.isInterface()).put("enum", type.isEnum());
        } else if ("methods".equals(kind)) {
            JSONArray methods = new JSONArray();
            for (Method method : type.getDeclaredMethods()) {
                JSONArray parameters = new JSONArray();
                for (Class<?> parameter : method.getParameterTypes()) parameters.put(parameter.getName());
                methods.put(new JSONObject().put("name", method.getName()).put("parameters", parameters)
                        .put("return_type", method.getReturnType().getName())
                        .put("static", Modifier.isStatic(method.getModifiers())));
            }
            result.put("methods", methods);
        } else if ("fields".equals(kind)) {
            JSONArray fields = new JSONArray();
            for (Field field : type.getDeclaredFields()) {
                fields.put(new JSONObject().put("name", field.getName()).put("type", field.getType().getName())
                        .put("static", Modifier.isStatic(field.getModifiers())));
            }
            result.put("fields", fields);
        }
        return result;
    }

    private void state(String id, RuleState state, String detail) {
        try {
            JSONObject request = new JSONObject().put("id", id).put("state", state.name()).put("package", packageName);
            request.put("event_order", stateOrder.incrementAndGet()).put("generation", generation);
            if (detail != null) request.put("detail", detail);
            channel.send("state", request);
        } catch (JSONException error) {
            XposedBridge.log("SimpleHook state serialization failed: " + error);
        }
    }

    private static String signature(HookRule rule) {
        return rule.target.className + "#" + (rule.target.constructor ? "<init>" : rule.target.method) + rule.target.parameters;
    }

    private static boolean sameTarget(HookRule left, HookRule right) {
        return java.util.Objects.equals(left.target.className, right.target.className)
                && java.util.Objects.equals(left.target.method, right.target.method)
                && left.target.constructor == right.target.constructor
                && left.target.parameters.equals(right.target.parameters)
                && java.util.Objects.equals(left.target.returnType, right.target.returnType)
                && java.util.Objects.equals(left.target.field, right.target.field);
    }
}
