package com.luckylca.runtimeinspector.testapp;

/** Explicit native entrypoint used only by the unified device regression fixture. */
public final class JniTraceProbe {
    static {
        System.loadLibrary("autocrack_jnitrace_probe");
    }

    private JniTraceProbe() {}

    public static native String run();
}
