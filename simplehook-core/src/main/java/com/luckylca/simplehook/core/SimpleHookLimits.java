package com.luckylca.simplehook.core;

public final class SimpleHookLimits {
    public static final int MAX_RULES = 256;
    public static final int MAX_HOOKED_METHODS = 512;
    public static final int MAX_WILDCARD_EXPANSION = 64;
    public static final int MAX_LOGS_PER_SECOND = 100;
    public static final int MAX_LOG_ENTRY_BYTES = 32 * 1024;
    public static final int MAX_STACK_TRACE_CHARS = 16 * 1024;
    public static final long MAX_LOG_FILE_BYTES = 4L * 1024L * 1024L;
    public static final int MAX_LOG_FILES = 4;

    private SimpleHookLimits() {}
}
