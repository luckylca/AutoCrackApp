package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    public XC_MethodHook() {}

    public XC_MethodHook(int priority) {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        public Object getResult() { throw new UnsupportedOperationException(); }
        public void setResult(Object result) { throw new UnsupportedOperationException(); }
        public Throwable getThrowable() { throw new UnsupportedOperationException(); }
        public boolean hasThrowable() { throw new UnsupportedOperationException(); }
        public void setThrowable(Throwable throwable) { throw new UnsupportedOperationException(); }
    }

    public final class Unhook {
        public Member getHookedMethod() { throw new UnsupportedOperationException(); }
        public void unhook() { throw new UnsupportedOperationException(); }
    }
}
