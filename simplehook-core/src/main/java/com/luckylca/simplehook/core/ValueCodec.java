package com.luckylca.simplehook.core;

import java.util.Map;
import java.util.regex.Pattern;
import org.json.JSONObject;

public final class ValueCodec {
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*(\\[\\])?");
    private static final Map<String, String> BOXED = Map.of(
            "java.lang.Boolean", "boolean",
            "java.lang.Byte", "byte",
            "java.lang.Short", "short",
            "java.lang.Integer", "int",
            "java.lang.Long", "long",
            "java.lang.Float", "float",
            "java.lang.Double", "double",
            "java.lang.Character", "char");

    private ValueCodec() {}

    public static boolean isValidTypeName(String type) {
        return type != null && (SetHolder.PRIMITIVES.contains(type) || CLASS_NAME.matcher(type).matches());
    }

    public static Object coerce(Object raw, String declaredType) {
        Object value = raw == JSONObject.NULL ? null : raw;
        String type = BOXED.getOrDefault(declaredType, declaredType);
        boolean primitive = SetHolder.PRIMITIVES.contains(declaredType) && !"void".equals(declaredType);
        if (value == null) {
            if (primitive) throw new RuleValidationException("NULL_FOR_PRIMITIVE", "null is not valid for " + declaredType);
            return null;
        }
        try {
            return switch (type) {
                case "boolean" -> requireBoolean(value);
                case "byte" -> checkedIntegral(value, Byte.MIN_VALUE, Byte.MAX_VALUE).byteValue();
                case "short" -> checkedIntegral(value, Short.MIN_VALUE, Short.MAX_VALUE).shortValue();
                case "int" -> checkedIntegral(value, Integer.MIN_VALUE, Integer.MAX_VALUE).intValue();
                case "long" -> checkedIntegral(value, Long.MIN_VALUE, Long.MAX_VALUE).longValue();
                case "float" -> requireNumber(value).floatValue();
                case "double" -> requireNumber(value).doubleValue();
                case "char" -> requireChar(value);
                case "java.lang.String", "String" -> requireString(value);
                default -> value;
            };
        } catch (ArithmeticException | ClassCastException exception) {
            throw new RuleValidationException("TYPE_MISMATCH", "Value is not compatible with " + declaredType);
        }
    }

    public static Class<?> resolveType(String name, ClassLoader loader) throws ClassNotFoundException {
        if (name.endsWith("[]")) {
            Class<?> component = resolveType(name.substring(0, name.length() - 2), loader);
            return java.lang.reflect.Array.newInstance(component, 0).getClass();
        }
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "char" -> char.class;
            case "void" -> void.class;
            case "String" -> String.class;
            default -> Class.forName(name, false, loader);
        };
    }

    private static Boolean requireBoolean(Object value) {
        if (!(value instanceof Boolean result)) throw new ClassCastException();
        return result;
    }

    private static Number requireNumber(Object value) {
        if (!(value instanceof Number result)) throw new ClassCastException();
        return result;
    }

    private static Long checkedIntegral(Object value, long min, long max) {
        Number number = requireNumber(value);
        double floating = number.doubleValue();
        long integral = number.longValue();
        if (!Double.isFinite(floating) || floating != integral || integral < min || integral > max) {
            throw new ArithmeticException();
        }
        return integral;
    }

    private static Character requireChar(Object value) {
        if (!(value instanceof String string) || string.length() != 1) throw new ClassCastException();
        return string.charAt(0);
    }

    private static String requireString(Object value) {
        if (!(value instanceof String string)) throw new ClassCastException();
        return string;
    }

    private static final class SetHolder {
        private static final java.util.Set<String> PRIMITIVES = java.util.Set.of(
                "boolean", "byte", "short", "int", "long", "float", "double", "char", "void");
    }
}
