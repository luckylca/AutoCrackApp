package com.luckylca.simplehook.core;

public final class ConditionEvaluator {
    private ConditionEvaluator() {}

    public static boolean matches(String operator, Object actual, Object expected) {
        return switch (operator) {
            case "eq" -> actual == null ? expected == null : actual.equals(expected);
            case "ne" -> actual == null ? expected != null : !actual.equals(expected);
            case "is_null" -> actual == null;
            case "not_null" -> actual != null;
            case "contains" -> actual instanceof String value && expected instanceof String wanted && value.contains(wanted);
            case "starts_with" -> actual instanceof String value && expected instanceof String wanted && value.startsWith(wanted);
            case "ends_with" -> actual instanceof String value && expected instanceof String wanted && value.endsWith(wanted);
            case "gt" -> compare(actual, expected, comparison -> comparison > 0);
            case "gte" -> compare(actual, expected, comparison -> comparison >= 0);
            case "lt" -> compare(actual, expected, comparison -> comparison < 0);
            case "lte" -> compare(actual, expected, comparison -> comparison <= 0);
            default -> false;
        };
    }

    private static boolean compare(
            Object actual,
            Object expected,
            java.util.function.IntPredicate predicate) {
        if (!(actual instanceof Number left) || !(expected instanceof Number right)) return false;
        return predicate.test(Double.compare(left.doubleValue(), right.doubleValue()));
    }
}
