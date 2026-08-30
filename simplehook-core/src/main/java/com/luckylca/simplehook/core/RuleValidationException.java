package com.luckylca.simplehook.core;

public final class RuleValidationException extends IllegalArgumentException {
    private final String code;

    public RuleValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
