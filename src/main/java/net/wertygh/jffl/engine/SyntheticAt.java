package net.wertygh.jffl.engine;

import net.wertygh.jffl.api.annotation.At;
import net.wertygh.jffl.api.annotation.ModifyConstant;

import java.lang.reflect.Proxy;

class SyntheticAt {
    static At fromModifyConstant(ModifyConstant mc) {
        double numericValue = Double.NaN;
        if (!Double.isNaN(mc.doubleValue())) {
            numericValue = mc.doubleValue();
        } else if (!Float.isNaN(mc.floatValue())) {
            numericValue = mc.floatValue();
        } else if (mc.longValue() != Long.MIN_VALUE) {
            numericValue = (double) mc.longValue();
        }
        final double finalNumeric = numericValue;
        return (At) Proxy.newProxyInstance(
                At.class.getClassLoader(),
                new Class<?>[]{At.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "value" -> At.Value.CONSTANT;
                    case "target" -> "";
                    case "line" -> -1;
                    case "stringValue" -> mc.stringValue();
                    case "intValue" -> mc.intValue();
                    case "doubleValue" -> finalNumeric;
                    case "ordinal" -> mc.ordinal();
                    case "shift" -> At.Shift.BEFORE;
                    case "annotationType" -> At.class;
                    case "toString" -> "@SyntheticAt(CONSTANT)";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultFor(method);
                });
    }

    private static Object defaultFor(java.lang.reflect.Method m) {
        Class<?> rt = m.getReturnType();
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        if (rt == boolean.class) return false;
        return null;
    }
}
