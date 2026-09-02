package com.luckylca.autocrack.runtime.shared;

import java.lang.ref.WeakReference;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Process-bound cross-tool object handles. Weak by default; pinning is explicit and bounded. */
public final class ObjectRegistry {
    public static final int MAX_HANDLES = 2048;
    public static final int MAX_PINNED_HANDLES = 128;
    public static final long DEFAULT_TTL_MS = 10 * 60_000L;
    private static final ObjectRegistry INSTANCE = new ObjectRegistry();
    private final SecureRandom random = new SecureRandom();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(32, .75f, true);
    private String packageName;
    private String processName;
    private int pid;

    public static ObjectRegistry get() { return INSTANCE; }
    private ObjectRegistry() {}

    public synchronized void bindProcess(String pkg, String process, int pid) {
        boolean changed = this.pid != 0 && (this.pid != pid
                || !Objects.equals(this.packageName, pkg)
                || !Objects.equals(this.processName, process));
        if (changed) entries.clear();
        this.packageName = pkg;
        this.processName = process;
        this.pid = pid;
    }

    public synchronized String put(Object value, boolean pin, String session) {
        if (value == null) return null;
        sweep();
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            Object current = entry.value();
            if (current == value && entry.matchesProcess(packageName, processName, pid)) {
                entry.lastAccessAt = now;
                entry.expiresAt = now + DEFAULT_TTL_MS;
                if (pin) pinEntry(entry, value);
                return item.getKey();
            }
        }
        evictToFit();
        if (pin && pinnedCount() >= MAX_PINNED_HANDLES) {
            throw new IllegalStateException("Maximum pinned object handle count exceeded");
        }
        String id;
        do { id = "obj_" + Long.toUnsignedString(random.nextLong(), 36); } while (entries.containsKey(id));
        entries.put(id, new Entry(value, pin, session, packageName, processName, pid, now));
        return id;
    }

    public synchronized Object get(String id) { return get(id, null); }

    /** When session is non-null, a handle created for another explicit session is rejected. */
    public synchronized Object get(String id, String session) {
        sweep();
        Entry entry = entries.get(id);
        if (entry == null || !entry.matchesProcess(packageName, processName, pid)) return null;
        if (session != null && entry.session != null && !Objects.equals(session, entry.session)) return null;
        Object value = entry.value();
        if (value == null) {
            entries.remove(id);
            return null;
        }
        long now = System.currentTimeMillis();
        entry.lastAccessAt = now;
        entry.expiresAt = now + DEFAULT_TTL_MS;
        return value;
    }

    public synchronized boolean pin(String id, boolean pin) {
        Object value = get(id);
        if (value == null) return false;
        Entry entry = entries.get(id);
        if (pin) pinEntry(entry, value); else entry.strong = null;
        return true;
    }

    public synchronized boolean release(String id) { return entries.remove(id) != null; }

    public synchronized int clearSession(String session) {
        int before = entries.size();
        entries.entrySet().removeIf(e -> Objects.equals(session, e.getValue().session));
        return before - entries.size();
    }

    public synchronized HandleInfo info(String id) {
        sweep();
        Entry entry = entries.get(id);
        if (entry == null || !entry.matchesProcess(packageName, processName, pid)) return null;
        Object value = entry.value();
        if (value == null) {
            entries.remove(id);
            return null;
        }
        return new HandleInfo(id, value.getClass().getName(), entry.strong != null, entry.session,
                entry.packageName, entry.processName, entry.pid, entry.createdAt,
                entry.lastAccessAt, entry.expiresAt);
    }

    public synchronized int size() { sweep(); return entries.size(); }
    public synchronized int pinnedCount() { int n = 0; for (Entry entry : entries.values()) if (entry.strong != null) n++; return n; }
    public synchronized String packageName() { return packageName; }
    public synchronized String processName() { return processName; }
    public synchronized int pid() { return pid; }

    private void pinEntry(Entry entry, Object value) {
        if (entry.strong != null) return;
        if (pinnedCount() >= MAX_PINNED_HANDLES) {
            throw new IllegalStateException("Maximum pinned object handle count exceeded");
        }
        entry.strong = value;
    }

    private void evictToFit() {
        while (entries.size() >= MAX_HANDLES) {
            String victim = null;
            for (java.util.Map.Entry<String, Entry> item : entries.entrySet()) {
                if (item.getValue().strong == null) { victim = item.getKey(); break; }
            }
            if (victim == null) throw new IllegalStateException("Object handle registry is full of pinned entries");
            entries.remove(victim);
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> !e.getValue().matchesProcess(packageName, processName, pid)
                || e.getValue().expiresAt < now
                || (e.getValue().strong == null && e.getValue().weak.get() == null));
    }

    public record HandleInfo(String id, String className, boolean pinned, String session,
            String packageName, String processName, int pid, long createdAt,
            long lastAccessAt, long expiresAt) {}

    private static final class Entry {
        final WeakReference<Object> weak;
        Object strong;
        final String session;
        final String packageName;
        final String processName;
        final int pid;
        final long createdAt;
        long lastAccessAt;
        long expiresAt;

        Entry(Object value, boolean pin, String session, String packageName, String processName, int pid, long now) {
            this.weak = new WeakReference<>(value);
            this.strong = pin ? value : null;
            this.session = session;
            this.packageName = packageName;
            this.processName = processName;
            this.pid = pid;
            this.createdAt = now;
            this.lastAccessAt = now;
            this.expiresAt = now + DEFAULT_TTL_MS;
        }

        Object value() { return strong != null ? strong : weak.get(); }
        boolean matchesProcess(String pkg, String process, int actualPid) {
            return pid == actualPid && Objects.equals(packageName, pkg) && Objects.equals(processName, process);
        }
    }
}
