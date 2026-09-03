package com.luckylca.runtimeinspector.testapp.delayed;

/** Class intentionally absent from the base APK classloader until the fixture provider loads its dex. */
public final class DelayedTarget {
    private DelayedTarget() {}

    public static String loaded() {
        return "delayed";
    }
}
