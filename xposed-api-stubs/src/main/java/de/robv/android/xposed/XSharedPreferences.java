package de.robv.android.xposed;

public final class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) {}
    public boolean makeWorldReadable() { return false; }
    public void reload() {}
    public String getString(String key, String defaultValue) { return defaultValue; }
    public long getLong(String key, long defaultValue) { return defaultValue; }
}
