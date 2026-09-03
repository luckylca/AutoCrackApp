package com.luckylca.runtimeinspector.testapp;

/** Stable owned Java fixtures shared by RuntimeInspector and SimpleHook device tests. */
public final class HookTargets {
    public static int staticField = 7;
    public int instanceField = 11;
    private final String constructorValue;

    public HookTargets() {
        this("default");
    }

    public HookTargets(String constructorValue) {
        this.constructorValue = constructorValue;
    }

    public int getInt() {
        return 42;
    }

    public boolean getBoolean() {
        return true;
    }

    public String getString() {
        return "runtime-test";
    }

    public int add(int a, int b) {
        return a + b;
    }

    public String overload(int x) {
        return "int:" + x;
    }

    public String overload(String x) {
        return "string:" + x;
    }

    public String constructorValue() {
        return constructorValue;
    }

    public void exceptionMethod() {
        throw new IllegalStateException("AutoCrack runtime test exception");
    }
}
