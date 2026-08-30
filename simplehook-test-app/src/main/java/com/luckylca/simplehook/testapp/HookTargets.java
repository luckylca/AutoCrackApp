package com.luckylca.simplehook.testapp;

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
        return "simplehook";
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
        throw new IllegalStateException("SimpleHook test exception");
    }
}
