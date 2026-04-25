package net.wertygh.jffl.engine;

import net.wertygh.jffl.api.CallbackInfo;
import net.wertygh.jffl.api.CallbackInfoReturnable;
import net.wertygh.jffl.api.IClassPatch;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;

import java.lang.reflect.Method;

public class CallbackSourceBuilder {
    public static String resolveCallbackOrString(IClassPatch patch, Method m, String methodName, boolean cancellable, CtMethod target) throws Exception {
        if (!EngineUtils.isCallbackMethod(m)) return "{" + m.invoke(patch) + "}";
        String selfExpr = Modifier.isStatic(target.getModifiers()) ? "null" : "$0";
        String[] argRefs = EngineUtils.argRefsForParams(target.getParameterTypes().length);
        StringBuilder sb = new StringBuilder("{");
        appendDispatchCore(sb, patch, m, methodName, selfExpr, argRefs, cancellable);
        if (cancellable) {
            try {
                CtClass rt = target.getReturnType();
                if (rt == CtClass.voidType) {
                    sb.append("if (jffl_ci.isCancelled()) {return;}");
                } else if (EngineUtils.isReturnableCallback(m)) {
                    sb.append("if (jffl_ci.isCancelled()) {");
                    appendCancelReturn(sb, rt);
                    sb.append("}");
                } else {
                    sb.append("if (jffl_ci.isCancelled()) {")
                      .append(EngineUtils.getDefaultReturnStatement(rt)).append("}");
                }
            } catch (NotFoundException e) {
                sb.append("if (jffl_ci.isCancelled()) {return;}");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    public static String buildBodyReplacementCallback(IClassPatch patch, Method m, String methodName, CtMethod target, CtClass[] paramTypes, boolean isStatic) throws Exception {
        CtClass returnType = target.getReturnType();
        String selfExpr = isStatic ? "null" : "$0";
        String[] argRefs = EngineUtils.argRefsForParams(paramTypes.length);
        StringBuilder sb = new StringBuilder("{");
        appendDispatchCore(sb, patch, m, methodName, selfExpr, argRefs, true);
        if (returnType == CtClass.voidType) {
        } else if (EngineUtils.isReturnableCallback(m)) {
            appendCancelReturn(sb, returnType);
        } else {
            sb.append(EngineUtils.getDefaultReturnStatement(returnType));
        }
        sb.append("}");
        return sb.toString();
    }

    public static String buildAddMethodCallback(IClassPatch patch, Method m, String methodName, CtClass returnType, CtClass[] paramTypes, boolean isStatic) {
        String selfExpr = isStatic ? "null" : "$0";
        String[] argRefs = EngineUtils.argRefsForParams(paramTypes.length);
        StringBuilder sb = new StringBuilder();
        appendDispatchCore(sb, patch, m, methodName, selfExpr, argRefs, true);
        if (returnType == CtClass.voidType) {
        } else if (EngineUtils.isReturnableCallback(m)) {
            appendCancelReturn(sb, returnType);
        } else {
            sb.append(EngineUtils.getDefaultReturnStatement(returnType));
        }
        return sb.toString();
    }

    public static String buildModifyReturnValueCallback(IClassPatch patch, Method m, String methodName, CtMethod target) throws Exception {
        CtClass returnType = target.getReturnType();
        CtClass[] paramTypes = target.getParameterTypes();
        String selfExpr = Modifier.isStatic(target.getModifiers()) ? "null" : "$0";
        String[] argRefs = EngineUtils.argRefsForParams(paramTypes.length);
        StringBuilder sb = new StringBuilder("{");
        boolean returnable = EngineUtils.isReturnableCallback(m);
        int id = appendCiDeclaration(sb, patch, m, methodName, true);
        if (returnable && returnType != CtClass.voidType) {
            sb.append("jffl_ci.setReturnValue(($w) $_);");
        }
        appendDispatchCall(sb, id, returnable, selfExpr, argRefs, stateKey(patch, m, methodName));
        if (returnable && returnType != CtClass.voidType) {
            appendValueWriteback(sb, "$_", returnType.getName());
        }
        sb.append("}");
        return sb.toString();
    }

    public static String buildTryCatchCallback(IClassPatch patch, Method m, String methodName, CtMethod target, CtClass exType) throws Exception {
        CtClass returnType = target.getReturnType();
        boolean isStatic = (target.getModifiers() & Modifier.STATIC) != 0;
        String selfExpr = isStatic ? "null" : "$0";
        StringBuilder sb = new StringBuilder("{");
        appendDispatchCore(sb, patch, m, methodName, selfExpr, new String[]{"$1"}, true);
        sb.append("if (jffl_ci.isCancelled()) {");
        if (returnType == CtClass.voidType) {
            sb.append("return;");
        } else if (EngineUtils.isReturnableCallback(m)) {
            appendCancelReturn(sb, returnType);
        } else {
            sb.append(EngineUtils.getDefaultReturnStatement(returnType));
        }
        sb.append("} else { throw $1; }");
        sb.append("}");
        return sb.toString();
    }

    public static String buildConstructorCallback(IClassPatch patch, Method m, String methodName, CtConstructor ctor) throws Exception {
        CtClass[] paramTypes = ctor.getParameterTypes();
        String[] argRefs = EngineUtils.argRefsForParams(paramTypes.length);
        StringBuilder sb = new StringBuilder("{");
        appendDispatchCore(sb, patch, m, methodName, "$0", argRefs, false);
        sb.append("}");
        return sb.toString();
    }

    public static String buildStaticInitCallback(IClassPatch patch, Method m, String methodName) {
        StringBuilder sb = new StringBuilder("{");
        appendDispatchCore(sb, patch, m, methodName, "null", new String[0], false);
        sb.append("}");
        return sb.toString();
    }

    public static String buildExprValueCallback(IClassPatch patch, Method m, String methodName, String returnTypeName, String selfExpr, String[] argRefs, String valueTypeName) {
        boolean returnable = EngineUtils.isReturnableCallback(m);
        StringBuilder sb = new StringBuilder("{");
        sb.append("$_ = $proceed($$);");
        if (returnable) {
            int id = appendCiDeclaration(sb, patch, m, methodName, true);
            sb.append("jffl_ci.setReturnValue(($w) $_);");
            appendDispatchCall(sb, id, true, selfExpr, argRefs, stateKey(patch, m, methodName));
            appendValueWriteback(sb, "$_", valueTypeName);
        } else {
            appendDispatchCore(sb, patch, m, methodName, selfExpr, argRefs, false);
        }
        sb.append("}");
        return sb.toString();
    }

    public static String buildExprInsertionCallback(IClassPatch patch, Method m, String methodName, String selfExpr, String[] argRefs) {
        StringBuilder sb = new StringBuilder("{");
        appendDispatchCore(sb, patch, m, methodName, selfExpr, argRefs, false);
        sb.append("}");
        return sb.toString();
    }

    public static String buildFieldWriteCallback(IClassPatch patch, Method m, String methodName, String selfExpr, String fieldType) {
        boolean returnable = EngineUtils.isReturnableCallback(m);
        StringBuilder sb = new StringBuilder("{");
        if (returnable) {
            int id = appendCiDeclaration(sb, patch, m, methodName, true);
            sb.append("jffl_ci.setReturnValue(($w) $1);");
            appendDispatchCall(sb, id, true, selfExpr, new String[]{"$1"}, stateKey(patch, m, methodName));
            appendValueWriteback(sb, "$1", fieldType);
        } else {
            appendDispatchCore(sb, patch, m, methodName, selfExpr, new String[]{"$1"}, false);
        }
        sb.append("$proceed($1);");
        sb.append("}");
        return sb.toString();
    }

    public static String buildModifyArgCallback(IClassPatch patch, Method m, String methodName, String selfExpr, String argRef, String argType) {
        boolean returnable = EngineUtils.isReturnableCallback(m);
        StringBuilder sb = new StringBuilder("{");
        if (returnable) {
            int id = appendCiDeclaration(sb, patch, m, methodName, true);
            sb.append("jffl_ci.setReturnValue(($w) ").append(argRef).append(");");
            appendDispatchCall(sb, id, true, selfExpr, new String[]{argRef}, stateKey(patch, m, methodName));
            appendValueWriteback(sb, argRef, argType);
        } else {
            appendDispatchCore(sb, patch, m, methodName, selfExpr, new String[]{argRef}, false);
        }
        sb.append("$_ = $proceed($$);");
        sb.append("}");
        return sb.toString();
    }

    public static String buildModifyVariableCallback(IClassPatch patch, Method m, String methodName, String selfExpr, String varRef, String varType) {
        boolean returnable = EngineUtils.isReturnableCallback(m);
        StringBuilder sb = new StringBuilder("{");
        if (returnable) {
            int id = appendCiDeclaration(sb, patch, m, methodName, true);
            sb.append("jffl_ci.setReturnValue(($w) ").append(varRef).append(");");
            appendDispatchCall(sb, id, true, selfExpr, new String[]{varRef}, stateKey(patch, m, methodName));
            appendValueWriteback(sb, varRef, varType);
        } else {
            appendDispatchCore(sb, patch, m, methodName, selfExpr, new String[]{varRef}, false);
        }
        sb.append("}");
        return sb.toString();
    }

    public static String buildModifyVariableLineCallback(IClassPatch patch, Method m, String methodName, String selfExpr) {
        StringBuilder sb = new StringBuilder("{");
        appendDispatchCore(sb, patch, m, methodName, selfExpr, new String[0], false);
        sb.append("}");
        return sb.toString();
    }

    public static void appendDispatchCore(StringBuilder sb, IClassPatch patch, Method m, String methodName, String selfExpr, String[] argRefs, boolean cancellable) {
        boolean returnable = EngineUtils.isReturnableCallback(m);
        int id = appendCiDeclaration(sb, patch, m, methodName, cancellable);
        appendDispatchCall(sb, id, returnable, selfExpr, argRefs, stateKey(patch, m, methodName));
    }

    private static int appendCiDeclaration(StringBuilder sb, IClassPatch patch, Method m, String methodName, boolean cancellable) {
        boolean returnable = EngineUtils.isReturnableCallback(m);
        int id = CallbackDispatcher.register(patch, m, returnable);
        String ciType = returnable ? CallbackInfoReturnable.class.getName() : CallbackInfo.class.getName();
        sb.append(ciType).append(" jffl_ci = new ").append(ciType)
          .append("(\"").append(methodName).append("\", ")
          .append(cancellable).append(");");
        return id;
    }

    private static void appendDispatchCall(StringBuilder sb, int id, boolean returnable, String selfExpr, String[] argRefs, String stateKey) {
        boolean stateful = CallbackDispatcher.handlerNeedsState(id);
        if (argRefs != null) {
            appendArgsArray(sb, argRefs);
            if (returnable) {
                sb.append(CallbackDispatcher.class.getName())
                  .append(stateful ? ".dispatchReturnableWithArgsState(" : ".dispatchReturnableWithArgs(")
                  .append(id);
                if (stateful) sb.append(", ").append(quote(stateKey));
                sb.append(", ").append(selfExpr).append(", jffl_args, (")
                  .append(CallbackInfoReturnable.class.getName()).append(") jffl_ci);");
            } else {
                sb.append(CallbackDispatcher.class.getName())
                  .append(stateful ? ".dispatchWithArgsState(" : ".dispatchWithArgs(")
                  .append(id);
                if (stateful) sb.append(", ").append(quote(stateKey));
                sb.append(", ").append(selfExpr).append(", jffl_args, jffl_ci);");
            }
        } else {
            if (returnable) {
                sb.append(CallbackDispatcher.class.getName())
                  .append(stateful ? ".dispatchReturnableState(" : ".dispatchReturnable(")
                  .append(id);
                if (stateful) sb.append(", ").append(quote(stateKey));
                sb.append(", ").append(selfExpr).append(", (")
                  .append(CallbackInfoReturnable.class.getName()).append(") jffl_ci);");
            } else {
                sb.append(CallbackDispatcher.class.getName())
                  .append(stateful ? ".dispatchState(" : ".dispatch(")
                  .append(id);
                if (stateful) sb.append(", ").append(quote(stateKey));
                sb.append(", ").append(selfExpr).append(", jffl_ci);");
            }
        }
    }

    public static boolean methodNeedsState(Method m) {
        return CallbackDispatcher.methodNeedsState(m);
    }

    public static String stateKey(IClassPatch patch, Method m, String methodName) {
        Class<?> patchClass = patch != null ? patch.getClass() : m.getDeclaringClass();
        return patchClass.getName() + "::" + methodName;
    }

    public static String stateEnterSource(IClassPatch patch, Method m, String methodName) {
        return CallbackDispatcher.class.getName() + ".enterStateFrame(" + quote(stateKey(patch, m, methodName)) + ");";
    }

    public static String stateEnterIfAbsentSource(IClassPatch patch, Method m, String methodName) {
        return CallbackDispatcher.class.getName() + ".enterStateFrameIfAbsent(" + quote(stateKey(patch, m, methodName)) + ");";
    }

    public static String stateExitSource(IClassPatch patch, Method m, String methodName) {
        return CallbackDispatcher.class.getName() + ".exitStateFrame(" + quote(stateKey(patch, m, methodName)) + ");";
    }

    public static String wrapWithStateEnter(IClassPatch patch, Method m, String methodName, String src) {
        if (!methodNeedsState(m)) return src;
        String body = EngineUtils.stripOuterBraces(src);
        return "{" + stateEnterIfAbsentSource(patch, m, methodName) + body + "}";
    }

    public static String quote(String value) {
        String s = value == null ? "" : value;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void appendArgsArray(StringBuilder sb, String[] argRefs) {
        sb.append("Object[] jffl_args = new Object[")
          .append(argRefs.length).append("];");
        for (int i = 0; i < argRefs.length; i++) {
            sb.append("jffl_args[").append(i).append("] = ($w) ")
              .append(argRefs[i]).append(";");
        }
    }

    public static void appendCancelReturn(StringBuilder sb, CtClass returnType) {
        String cirCast = "((" + CallbackInfoReturnable.class.getName() + ") jffl_ci)";
        if (returnType.isPrimitive()) {
            String boxed = EngineUtils.getBoxedName(returnType);
            String unbox = EngineUtils.getUnboxExpression(returnType);
            sb.append("return ((").append(boxed).append(")").append(cirCast)
              .append(".getReturnValue()).").append(unbox).append(";");
        } else {
            sb.append("return (").append(returnType.getName()).append(")")
              .append(cirCast).append(".getReturnValue();");
        }
    }

    public static void appendReturnCast(StringBuilder sb, CtClass returnType) {
        if (returnType.isPrimitive()) {
            String boxed = EngineUtils.getBoxedName(returnType);
            String unbox = EngineUtils.getUnboxExpression(returnType);
            sb.append("return ((").append(boxed)
              .append(") jffl_cir.getReturnValue()).").append(unbox).append(";");
        } else {
            sb.append("return (").append(returnType.getName())
              .append(") jffl_cir.getReturnValue();");
        }
    }

    public static void appendValueWriteback(StringBuilder sb, String lhs, String typeName) {
        if (EngineUtils.isPrimitiveTypeName(typeName)) {
            sb.append(lhs).append(" = ((")
              .append(EngineUtils.getBoxedNameFromPrimitiveName(typeName))
              .append(") jffl_ci.getReturnValue()).")
              .append(EngineUtils.getUnboxFromPrimitiveName(typeName)).append(";");
        } else {
            sb.append(lhs).append(" = (").append(typeName)
              .append(") jffl_ci.getReturnValue();");
        }
    }
}
