package net.wertygh.jffl.engine;

import net.wertygh.jffl.api.CallbackInfo;
import net.wertygh.jffl.api.CallbackInfoReturnable;
import net.wertygh.jffl.api.annotation.AddMethod;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.Descriptor;
import javassist.expr.MethodCall;

import java.lang.reflect.Method;

public class EngineUtils {
    public static boolean hasMethod(CtClass cc, String name) {
        for (CtMethod m : cc.getDeclaredMethods()) {
            if (m.getName().equals(name)) return true;
        }
        return false;
    }

    public static boolean hasMethod(CtClass cc, String name, String desc) {
        for (CtMethod m : cc.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (desc == null || desc.isEmpty()) return true;
            if (desc.equals(m.getSignature()) || desc.equals(m.getMethodInfo().getDescriptor())) return true;
        }
        return false;
    }

    public static CtMethod findMethod(CtClass ctClass, String name, String desc) throws NotFoundException {
        if (desc == null || desc.isEmpty()) return ctClass.getDeclaredMethod(name);
        for (CtMethod m : ctClass.getDeclaredMethods(name)) {
            if (m.getSignature().equals(desc) || m.getMethodInfo().getDescriptor().equals(desc)) {
                return m;
            }
        }
        throw new NotFoundException("在" + ctClass.getName() + "上未找到方法" + name + desc);
    }

    public static boolean hasField(CtClass cc, String name) {
        for (CtField f : cc.getDeclaredFields()) {
            if (f.getName().equals(name)) return true;
        }
        return false;
    }

    public static CtClass resolveType(ClassPool pool, String typeName) throws NotFoundException {
        return switch (typeName) {
            case "boolean" -> CtClass.booleanType;
            case "byte" -> CtClass.byteType;
            case "char" -> CtClass.charType;
            case "short" -> CtClass.shortType;
            case "int" -> CtClass.intType;
            case "long" -> CtClass.longType;
            case "float" -> CtClass.floatType;
            case "double" -> CtClass.doubleType;
            case "void" -> CtClass.voidType;
            default -> pool.get(typeName);
        };
    }

    public static CtClass[] parseParamTypes(ClassPool pool, String params) throws NotFoundException {
        if (params == null || params.trim().isEmpty()) return new CtClass[0];
        String[] parts = params.split(",");
        CtClass[] result = new CtClass[parts.length];
        for (int i=0;i<parts.length;i++) {
            String p = parts[i].trim();
            int spaceIdx = p.lastIndexOf(' ');
            String typeName = spaceIdx > 0 ? p.substring(0, spaceIdx).trim() : p;
            result[i] = resolveType(pool, typeName);
        }
        return result;
    }

    public static String buildMethodSource(AddMethod am, String body) {
        StringBuilder sb = new StringBuilder();
        if ((am.access() & Modifier.PUBLIC) != 0) sb.append("public ");
        if ((am.access() & Modifier.PROTECTED) != 0) sb.append("protected ");
        if ((am.access() & Modifier.PRIVATE) != 0) sb.append("private ");
        if ((am.access() & Modifier.STATIC) != 0) sb.append("static ");
        sb.append(am.returnType()).append(' ').append(am.name());
        sb.append('(').append(am.params()).append(')');
        sb.append(" { ").append(body).append(" }");
        return sb.toString();
    }

    public static boolean matchesFull(MethodCall mc, String target) {
        String full = mc.getClassName() + "." + mc.getMethodName() + mc.getSignature();
        if (target.equals(full)) return true;
        String shortForm = mc.getMethodName() + mc.getSignature();
        return target.equals(shortForm);
    }

    public static String stripOuterBraces(String src) {
        if (src == null) return "";
        String s = src.trim();
        if (s.startsWith("{") && s.endsWith("}")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    public static String[] argRefsForParams(int count) {
        String[] refs = new String[count];
        for (int i=0;i<count;i++) refs[i] = "$" + (i + 1);
        return refs;
    }

    public static boolean isCallbackMethod(Method m) {
        if (m.getReturnType() != void.class) return false;
        Class<?>[] params = m.getParameterTypes();
        if (params.length == 0) return false;
        return CallbackInfo.class.isAssignableFrom(params[params.length - 1]);
    }

    public static boolean isReturnableCallback(Method m) {
        Class<?>[] params = m.getParameterTypes();
        if (params.length == 0) return false;
        return CallbackInfoReturnable.class.isAssignableFrom(params[params.length - 1]);
    }

    public static boolean callbackAcceptsArgs(Method m) {
        Class<?>[] params = m.getParameterTypes();
        return params.length >= 3
                && params[0] == Object.class
                && params[1] == Object[].class
                && CallbackInfo.class.isAssignableFrom(params[params.length - 1]);
    }

    public static int callbackPayloadParamCount(Method m) {
        Class<?>[] params = m.getParameterTypes();
        return Math.max(0, params.length - 1);
    }

    public static boolean callbackMayConsumeTargetArgs(Method m) {
        return callbackPayloadParamCount(m) > 0;
    }

    public static Class<?> lastParam(Method m) {
        Class<?>[] p = m.getParameterTypes();
        return p[p.length - 1];
    }

    public static String getBoxedName(CtClass primitive) {
        if (primitive == CtClass.intType) return "java.lang.Integer";
        if (primitive == CtClass.longType) return "java.lang.Long";
        if (primitive == CtClass.floatType) return "java.lang.Float";
        if (primitive == CtClass.doubleType) return "java.lang.Double";
        if (primitive == CtClass.booleanType) return "java.lang.Boolean";
        if (primitive == CtClass.byteType) return "java.lang.Byte";
        if (primitive == CtClass.charType) return "java.lang.Character";
        if (primitive == CtClass.shortType) return "java.lang.Short";
        return "java.lang.Object";
    }

    public static String getUnboxExpression(CtClass primitive) {
        if (primitive == CtClass.intType) return "intValue()";
        if (primitive == CtClass.longType) return "longValue()";
        if (primitive == CtClass.floatType) return "floatValue()";
        if (primitive == CtClass.doubleType) return "doubleValue()";
        if (primitive == CtClass.booleanType) return "booleanValue()";
        if (primitive == CtClass.byteType) return "byteValue()";
        if (primitive == CtClass.charType) return "charValue()";
        if (primitive == CtClass.shortType) return "shortValue()";
        return "toString()";
    }

    public static boolean isPrimitiveTypeName(String name) {
        if (name == null) return false;
        return switch (name) {
            case "boolean", "byte", "char", "short",
                 "int", "long", "float", "double", "void" -> true;
            default -> false;
        };
    }

    public static String getBoxedNameFromPrimitiveName(String name) {
        return switch (name) {
            case "boolean" -> "java.lang.Boolean";
            case "byte" -> "java.lang.Byte";
            case "char" -> "java.lang.Character";
            case "short" -> "java.lang.Short";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "float" -> "java.lang.Float";
            case "double" -> "java.lang.Double";
            default -> "java.lang.Object";
        };
    }

    public static String getUnboxFromPrimitiveName(String name) {
        return switch (name) {
            case "boolean" -> "booleanValue()";
            case "byte" -> "byteValue()";
            case "char" -> "charValue()";
            case "short" -> "shortValue()";
            case "int" -> "intValue()";
            case "long" -> "longValue()";
            case "float" -> "floatValue()";
            case "double" -> "doubleValue()";
            default -> "toString()";
        };
    }

    public static String getDefaultReturnStatement(CtClass rt) {
        if (rt == CtClass.booleanType) return "return false;";
        if (rt == CtClass.byteType || rt == CtClass.shortType
                || rt == CtClass.intType || rt == CtClass.charType) return "return 0;";
        if (rt == CtClass.longType)   return "return 0L;";
        if (rt == CtClass.floatType)  return "return 0.0f;";
        if (rt == CtClass.doubleType) return "return 0.0;";
        return "return null;";
    }

    public static String methodKey(CtMethod method) {
        return methodKey(method.getName(), method.getMethodInfo().getDescriptor());
    }

    public static String methodKey(String name, String desc) {
        return name + (desc == null ? "" : desc);
    }

    public static String buildMethodDescriptor(CtClass returnType, CtClass[] paramTypes) {
        return Descriptor.ofMethod(returnType, paramTypes);
    }

    public static String safePatchId(String patchClassName) {
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<patchClassName.length();i++) {
            char c = patchClassName.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else sb.append('_');
        }
        return sb.toString();
    }
}
