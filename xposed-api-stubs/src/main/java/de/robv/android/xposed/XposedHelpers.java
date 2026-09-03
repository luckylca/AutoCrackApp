package de.robv.android.xposed;

/** Compile-only subset used by AutoCrack. Real implementations are supplied by Xposed/LSPosed. */
public final class XposedHelpers {
    private XposedHelpers() {}

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException();
    }

    public static long getLongField(Object obj, String fieldName) {
        throw new UnsupportedOperationException();
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException();
    }
}
