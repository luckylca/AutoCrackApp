package com.luckylca.simplehook.testapp;

public final class DelayedTarget {
    private DelayedTarget() {}

    public static String loaded() {
        return "delayed";
    }
}
