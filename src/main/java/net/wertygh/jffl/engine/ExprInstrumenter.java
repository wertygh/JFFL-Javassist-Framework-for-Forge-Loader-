package net.wertygh.jffl.engine;

import net.wertygh.jffl.api.*;
import net.wertygh.jffl.api.annotation.*;
import javassist.*;
import javassist.bytecode.AccessFlag;
import javassist.expr.*;

import java.lang.reflect.Method;

public class ExprInstrumenter {
    public static void applyInstrumentInstanceof(CtClass ctClass, IClassPatch patch, Method handlerMethod, InstrumentInstanceof ann, PatchContext ctx) throws Exception {
        CtMethod target = EngineUtils.findMethod(ctClass, ann.method(), ann.desc());
        boolean callback = EngineUtils.isCallbackMethod(handlerMethod);
        String replacement = callback ? null : (String) handlerMethod.invoke(patch);
        String callbackReplacement = callback
                ? CallbackSourceBuilder.buildExprValueCallback(
                    patch, handlerMethod, ann.method(), "boolean", 
                    Modifier.isStatic(target.getModifiers()) ? "null" : "$0", 
                    new String[]{"$1"}, "boolean"
                )
                : null;
        String targetType = ann.target();
        int wantOrdinal = ann.ordinal();
        int[] seen = {0};
        target.instrument(new ExprEditor() {
            @Override public void edit(Instanceof i) throws CannotCompileException {
                try {
                    if (!targetType.isEmpty() && !i.getType().getName().equals(targetType)) return;
                } catch (NotFoundException e) { return; }
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                i.replace(callback ? callbackReplacement : "{" + replacement + "}");
            }
        });
    }

    public static void applyInstrumentHandler(CtClass ctClass, IClassPatch patch, Method handlerMethod, InstrumentHandler ann, PatchContext ctx) throws Exception {
        CtMethod target = EngineUtils.findMethod(ctClass, ann.method(), ann.desc());
        boolean callback = EngineUtils.isCallbackMethod(handlerMethod);
        String replacement = callback
                ? CallbackSourceBuilder.buildExprInsertionCallback(
                    patch, handlerMethod, ann.method(), 
                    Modifier.isStatic(target.getModifiers()) ? "null" : "$0", 
                    new String[]{"$1"}
                )
                : (String) handlerMethod.invoke(patch);
        String exType = ann.exceptionType();
        int wantOrdinal = ann.ordinal();
        int[] seen = {0};
        target.instrument(new ExprEditor() {
            @Override public void edit(Handler h) throws CannotCompileException {
                try {
                    if (!exType.isEmpty()) {
                        CtClass caught = h.getType();
                        if (caught == null || !caught.getName().equals(exType)) return;
                    }
                } catch (NotFoundException e) { return; }
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                h.insertBefore(callback ? replacement : "{" + replacement + "}");
            }
        });
    }

    public static void applyInstrumentFieldAccess(CtClass ctClass, IClassPatch patch, Method handlerMethod, InstrumentFieldAccess ann, PatchContext ctx) throws Exception {
        CtMethod target = EngineUtils.findMethod(ctClass, ann.method(), ann.desc());
        boolean callback = EngineUtils.isCallbackMethod(handlerMethod);
        String replacement = callback ? null : (String) handlerMethod.invoke(patch);
        String fieldTarget = ann.target();
        InstrumentFieldAccess.AccessType accessType = ann.accessType();
        int wantOrdinal = ann.ordinal();
        int[] seen = {0};
        target.instrument(new ExprEditor() {
            @Override public void edit(FieldAccess fa) throws CannotCompileException {
                if (accessType == InstrumentFieldAccess.AccessType.READ  && fa.isWriter()) return;
                if (accessType == InstrumentFieldAccess.AccessType.WRITE && fa.isReader()) return;
                if (!fieldTarget.isEmpty()) {
                    if (fieldTarget.contains(".")) {
                        String full = fa.getClassName() + "." + fa.getFieldName();
                        if (!fieldTarget.equals(full)) return;
                    } else {
                        if (!fieldTarget.equals(fa.getFieldName())) return;
                    }
                }
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                if (callback) {
                    String fieldType;
                    try {
                        fieldType = fa.getField().getType().getName();
                    } catch (NotFoundException e) {
                        fieldType = "java.lang.Object";
                    }
                    if (fa.isReader()) {
                        String src = CallbackSourceBuilder.buildExprValueCallback(
                            patch, handlerMethod, ann.method(), fieldType, 
                            Modifier.isStatic(target.getModifiers()) ? "null" : "$0", 
                            new String[0], fieldType
                        );
                        fa.replace(src);
                    } else {
                        String src = CallbackSourceBuilder.buildFieldWriteCallback(
                            patch, handlerMethod, ann.method(), 
                            Modifier.isStatic(target.getModifiers()) ? "null" : "$0", 
                            fieldType
                        );
                        fa.replace(src);
                    }
                } else {
                    fa.replace("{" + replacement + "}");
                }
            }
        });
    }

    public static void applyInstrumentConstructorCall(CtClass ctClass, IClassPatch patch, Method handlerMethod, InstrumentConstructorCall ann, PatchContext ctx) throws Exception {
        CtMethod target = EngineUtils.findMethod(ctClass, ann.method(), ann.desc());
        boolean callback = EngineUtils.isCallbackMethod(handlerMethod);
        String replacement = callback ? null : (String) handlerMethod.invoke(patch);
        String targetType = ann.target();
        int wantOrdinal = ann.ordinal();
        int[] seen = {0};
        target.instrument(new ExprEditor() {
            @Override public void edit(ConstructorCall c) throws CannotCompileException {
                if (!targetType.isEmpty() && !c.getClassName().equals(targetType)
                        && !c.getClassName().replace('.', '/')
                                .equals(targetType.replace('.', '/'))) return;
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                if (callback) {
                    String dispatchSrc = CallbackSourceBuilder.buildExprInsertionCallback(
                        patch, handlerMethod, ann.method(), 
                        Modifier.isStatic(target.getModifiers()) ? "null" : "$0", 
                        new String[0]
                    );
                    c.replace("{" + EngineUtils.stripOuterBraces(dispatchSrc) + " $proceed($$);}");
                } else {
                    c.replace("{" + replacement + "}");
                }
            }
            @Override public void edit(NewExpr e) throws CannotCompileException {
                if (!targetType.isEmpty() && !e.getClassName().equals(targetType)
                        && !e.getClassName().replace('.', '/')
                                .equals(targetType.replace('.', '/'))) return;
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                if (callback) {
                    String typeName = e.getClassName();
                    String src = CallbackSourceBuilder.buildExprValueCallback(
                        patch, handlerMethod, ann.method(), typeName, 
                        Modifier.isStatic(target.getModifiers()) ? "null" : "$0", 
                        new String[0], typeName
                    );
                    e.replace(src);
                } else {
                    e.replace("{" + replacement + "}");
                }
            }
        });
    }

    public static void applyInstrumentNewExpr(CtClass ctClass, IClassPatch patch, Method handlerMethod, InstrumentNewExpr ine, PatchContext ctx) throws Exception {
        CtMethod target = EngineUtils.findMethod(ctClass, ine.method(), ine.desc());
        boolean callback = EngineUtils.isCallbackMethod(handlerMethod);
        String replacement = callback ? null : (String) handlerMethod.invoke(patch);
        String targetType = ine.target().replace('.', '/');
        int wantOrdinal = ine.ordinal();
        int[] seen = {0};
        target.instrument(new ExprEditor() {
            @Override public void edit(NewExpr e) throws CannotCompileException {
                if (!targetType.isEmpty()
                        && !e.getClassName().replace('.', '/').equals(targetType)
                        && !e.getClassName().equals(ine.target())) return;
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                if (callback) {
                    String typeName = e.getClassName();
                    String src = CallbackSourceBuilder.buildExprValueCallback(
                        patch, handlerMethod, ine.method(), typeName, 
                        Modifier.isStatic(target.getModifiers()) ? "null" : "$0", 
                        new String[0], typeName
                    );
                    e.replace(src);
                } else {
                    e.replace("{" + replacement + "}");
                }
            }
        });
    }

    public static void applyInstrumentCast(CtClass ctClass, IClassPatch patch, Method handlerMethod, InstrumentCast ic, PatchContext ctx) throws Exception {
        CtMethod target = EngineUtils.findMethod(ctClass, ic.method(), ic.desc());
        boolean callback = EngineUtils.isCallbackMethod(handlerMethod);
        String replacement = callback ? null : (String) handlerMethod.invoke(patch);
        String targetType = ic.target();
        int wantOrdinal = ic.ordinal();
        int[] seen = {0};
        target.instrument(new ExprEditor() {
            @Override public void edit(Cast c) throws CannotCompileException {
                String typeName;
                try {
                    typeName = c.getType().getName();
                    if (!targetType.isEmpty() && !typeName.equals(targetType)) return;
                } catch (NotFoundException e) { return; }
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                if (callback) {
                    String src = CallbackSourceBuilder.buildExprValueCallback(
                        patch, handlerMethod, ic.method(), typeName, 
                        Modifier.isStatic(target.getModifiers()) ? "null" : "$0", 
                        new String[]{"$1"}, typeName
                    );
                    c.replace(src);
                } else {
                    c.replace("{" + replacement + "}");
                }
            }
        });
    }

    public static void applyModifyArg(CtClass ctClass, IClassPatch patch, Method handlerMethod, ModifyArg ma, PatchContext ctx) throws Exception {
        CtMethod target = EngineUtils.findMethod(ctClass, ma.method(), ma.desc());
        boolean callback = EngineUtils.isCallbackMethod(handlerMethod);
        String replacement = callback ? null : (String) handlerMethod.invoke(patch);
        int argIndex = ma.index();
        String invokeTarget = ma.target();
        int wantOrdinal = ma.ordinal();
        int[] seen = {0};
        int[] matched = {0};
        target.instrument(new ExprEditor() {
            @Override public void edit(MethodCall mc) throws CannotCompileException {
                if (!invokeTarget.isEmpty() && !mc.getMethodName().equals(invokeTarget)
                        && !EngineUtils.matchesFull(mc, invokeTarget)) return;
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                matched[0]++;
                String argRef = "$" + (argIndex + 1);
                if (callback) {
                    String argType;
                    try {
                        CtClass[] ptypes = mc.getMethod().getParameterTypes();
                        argType = (argIndex < ptypes.length) ? ptypes[argIndex].getName() : "java.lang.Object";
                    } catch (NotFoundException e) {
                        argType = "java.lang.Object";
                    }
                    String body = CallbackSourceBuilder.buildModifyArgCallback(
                        patch, handlerMethod, ma.method(), 
                        Modifier.isStatic(target.getModifiers()) ? "null" : "$0", 
                        argRef, argType
                    );
                    mc.replace(body);
                } else {
                    String body = "{" + argRef + " = " + replacement + "; $_ = $proceed($$);}";
                    mc.replace(body);
                }
            }
        });
        if (matched[0] == 0) {
            ctx.warn("@ModifyArg错误" + target.getDeclaringClass().getName() + "." + target.getName() + " target=" + invokeTarget + " ordinal=" + wantOrdinal);
        }
    }

    public static void applyModifyVariable(CtClass ctClass, IClassPatch patch, Method handlerMethod, ModifyVariable mv, PatchContext ctx) throws Exception {
        CtMethod target = EngineUtils.findMethod(ctClass, mv.method(), mv.desc());
        boolean callback = EngineUtils.isCallbackMethod(handlerMethod);
        if (mv.line() > 0) {
            String modification = callback
                    ? CallbackSourceBuilder.buildModifyVariableLineCallback(
                        patch, handlerMethod, mv.method(), 
                        Modifier.isStatic(target.getModifiers()) ? "null" : "$0"
                    )
                    : "{" + (String) handlerMethod.invoke(patch) + "}";
            target.insertAt(mv.line(), modification);
        } else if (mv.index() >= 0) {
            String varRef = "$" + mv.index();
            String src;
            if (callback) {
                src = CallbackSourceBuilder.buildModifyVariableCallback(
                    patch, handlerMethod, mv.method(),
                    Modifier.isStatic(target.getModifiers()) ? "null" : "$0", 
                    varRef, "java.lang.Object"
                );
            } else {
                src = "{" + varRef + " = " + (String) handlerMethod.invoke(patch) + ";}";
            }
            InjectionPoints.apply(target, mv.at(), src);
        } else if (!mv.name().isEmpty()) {
            String src;
            if (callback) {
                src = CallbackSourceBuilder.buildModifyVariableCallback(
                    patch, handlerMethod, mv.method(),
                    Modifier.isStatic(target.getModifiers()) ? "null" : "$0", 
                    mv.name(), "java.lang.Object"
                );
            } else {
                src = "{" + mv.name() + " = " + (String) handlerMethod.invoke(patch)+";}";
            }
            InjectionPoints.apply(target, mv.at(), src);
        }
    }

    public static void applyWrapMethod(CtClass ctClass, IClassPatch patch, Method handlerMethod, WrapMethod wm, PatchContext ctx) throws Exception {
        CtMethod target = EngineUtils.findMethod(ctClass, wm.method(), wm.desc());
        String baseWrappedName = "jffl$wrapped$" + wm.method() + "$" + EngineUtils.safePatchId(handlerMethod.getDeclaringClass().getName());
        String wrappedName = ctx.nextSyntheticMethodName(baseWrappedName);
        while (EngineUtils.hasMethod(ctClass, wrappedName, target.getMethodInfo().getDescriptor())) {
            wrappedName = ctx.nextSyntheticMethodName(baseWrappedName);
        }
        CtMethod clone = CtNewMethod.copy(target, wrappedName, ctClass, null);
        clone.setModifiers((clone.getModifiers() & ~(Modifier.PRIVATE | Modifier.PROTECTED)) | Modifier.PUBLIC);
        clone.getMethodInfo().setAccessFlags(clone.getMethodInfo().getAccessFlags() | AccessFlag.SYNTHETIC);
        ctClass.addMethod(clone);
        boolean isStatic = Modifier.isStatic(target.getModifiers());
        String originalHolderExpr = isStatic ? ctClass.getName() + ".class" : "$0";
        String selfExpr = isStatic ? "null" : "$0";
        int id = CallbackDispatcher.register(patch, handlerMethod, true);
        CtClass returnType = target.getReturnType();
        CtClass[] paramTypes = target.getParameterTypes();
        StringBuilder sb = new StringBuilder("{");
        sb.append("Object[] jffl_args = new Object[")
          .append(paramTypes.length).append("];");
        for (int i=0;i<paramTypes.length;i++) {
            sb.append("jffl_args[").append(i)
              .append("] = ($w)$").append(i + 1).append(";");
        }
        sb.append("java.util.concurrent.Callable jffl_original = ")
          .append(CallbackDispatcher.class.getName())
          .append(".createOriginalCaller(").append(originalHolderExpr).append(", \"")
          .append(wrappedName).append("\", jffl_args);");
        sb.append(CallbackInfoReturnable.class.getName())
          .append(" jffl_cir = new ").append(CallbackInfoReturnable.class.getName())
          .append("(\"").append(wm.method()).append("\", true);");
        sb.append(CallbackDispatcher.class.getName())
          .append(CallbackDispatcher.handlerNeedsState(id) ? ".dispatchWrapState(" : ".dispatchWrap(")
          .append(id);
        if (CallbackDispatcher.handlerNeedsState(id)) {
            sb.append(", ").append(CallbackSourceBuilder.quote(CallbackSourceBuilder.stateKey(patch, handlerMethod, wm.method())));
        }
        sb.append(", ").append(selfExpr).append(", jffl_args, jffl_original, jffl_cir);");
        if (returnType == CtClass.voidType) {
            sb.append("}");
        } else {
            CallbackSourceBuilder.appendReturnCast(sb, returnType);
            sb.append("}");
        }
        target.setBody(sb.toString());
    }

    public static void applyWrapOperation(CtClass ctClass, IClassPatch patch, Method handlerMethod, WrapOperation wop, PatchContext ctx) throws Exception {
        CtMethod target = EngineUtils.findMethod(ctClass, wop.method(), wop.desc());
        At at = wop.at();
        int[] sliceRange = InjectionPoints.resolveSliceRange(target, wop.slice());
        int sliceFrom = sliceRange[0];
        int sliceTo = sliceRange[1];
        int wantOrdinal = at.ordinal();
        String targetCallee = at.target();
        int[] seen = {0};
        int[] matched = {0};
        int handlerId = CallbackDispatcher.register(patch, handlerMethod, true);
        String selfExpr = Modifier.isStatic(target.getModifiers()) ? "null" : "$0";
        if (at.value() == At.Value.INVOKE) {
            target.instrument(new ExprEditor() {
                @Override public void edit(MethodCall mc) throws CannotCompileException {
                    if (!isLineInSlice(mc.getLineNumber(), sliceFrom, sliceTo)) return;
                    if (!matchesInvokeTarget(mc, targetCallee)) return;
                    int idx = seen[0]++;
                    if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                    matched[0]++;
                    try {
                        String body = buildWrapOperationInvoke(handlerId, CallbackSourceBuilder.stateKey(patch, handlerMethod, wop.method()), mc, selfExpr);
                        mc.replace(body);
                    } catch (NotFoundException e) {
                        throw new CannotCompileException(e);
                    }
                }
            });
        } else if (at.value() == At.Value.FIELD) {
            target.instrument(new ExprEditor() {
                @Override public void edit(FieldAccess fa) throws CannotCompileException {
                    if (!isLineInSlice(fa.getLineNumber(), sliceFrom, sliceTo)) return;
                    if (!targetCallee.isEmpty() && !targetCallee.equals(fa.getFieldName())) return;
                    int idx = seen[0]++;
                    if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                    matched[0]++;
                    String body = buildWrapOperationField(handlerId, CallbackSourceBuilder.stateKey(patch, handlerMethod, wop.method()), fa, selfExpr);
                    fa.replace(body);
                }
            });
        } else {
            throw new CannotCompileException("@WrapOperation仅支持@At(INVOKE)和@At(FIELD)");
        }

        if (matched[0] == 0) {
            ctx.warn("@WrapOperation错误" + target.getDeclaringClass().getName() + "." + target.getName() + " at=" + at.value() + " target=" + targetCallee + " ordinal=" + wantOrdinal);
        }
    }

    private static boolean isLineInSlice(int lineNumber, int sliceFrom, int sliceTo) {
        if (sliceFrom < 0 && sliceTo == Integer.MAX_VALUE) return true;
        if (lineNumber <= 0) return true;
        return lineNumber >= sliceFrom && lineNumber <= sliceTo;
    }

    private static boolean matchesInvokeTarget(MethodCall mc, String target) {
        if (target == null || target.isEmpty()) return true;
        if (target.equals(mc.getMethodName())) return true;
        String full = mc.getClassName() + "." + mc.getMethodName() + mc.getSignature();
        if (target.equals(full)) return true;
        String shortForm = mc.getMethodName() + mc.getSignature();
        return target.equals(shortForm);
    }

    private static String buildWrapOperationInvoke(int handlerId, String stateKey, MethodCall mc, String selfExpr) throws NotFoundException {
        CtClass[] paramTypes = mc.getMethod().getParameterTypes();
        CtClass returnType = mc.getMethod().getReturnType();
        StringBuilder sb = new StringBuilder("{");
        sb.append("Object[] jffl_args = new Object[").append(paramTypes.length).append("];");
        for (int i=0;i<paramTypes.length;i++) {
            sb.append("jffl_args[").append(i).append("] = ($w)$").append(i + 1).append(";");
        }
        sb.append("java.util.concurrent.Callable jffl_original = new java.util.concurrent.Callable() {")
          .append("  public Object call() throws Exception {return ($w) $proceed($$);}")
          .append("};");
        sb.append(CallbackInfoReturnable.class.getName())
          .append(" jffl_cir = new ").append(CallbackInfoReturnable.class.getName())
          .append("(\"").append(mc.getMethodName()).append("\", true);");
        sb.append(CallbackDispatcher.class.getName())
          .append(CallbackDispatcher.handlerNeedsState(handlerId) ? ".dispatchWrapState(" : ".dispatchWrap(")
          .append(handlerId);
        if (CallbackDispatcher.handlerNeedsState(handlerId)) {
            sb.append(", ").append(CallbackSourceBuilder.quote(stateKey));
        }
        sb.append(", ").append(selfExpr)
          .append(", jffl_args, jffl_original, jffl_cir);");
        if (returnType == CtClass.voidType) {
            sb.append("}");
        } else {
            CallbackSourceBuilder.appendReturnCast(sb, returnType);
            sb.append("}");
        }
        return sb.toString();
    }

    private static String buildWrapOperationField(int handlerId, String stateKey, FieldAccess fa, String selfExpr) {
        StringBuilder sb = new StringBuilder("{");
        if (fa.isReader()) {
            sb.append("Object[] jffl_args = new Object[0];");
            sb.append("java.util.concurrent.Callable jffl_original = new java.util.concurrent.Callable() {")
              .append("  public Object call() throws Exception {return ($w) $proceed();}")
              .append("};");
            sb.append(CallbackInfoReturnable.class.getName())
              .append(" jffl_cir = new ").append(CallbackInfoReturnable.class.getName())
              .append("(\"").append(fa.getFieldName()).append("\", true);");
            sb.append(CallbackDispatcher.class.getName())
              .append(CallbackDispatcher.handlerNeedsState(handlerId) ? ".dispatchWrapState(" : ".dispatchWrap(")
              .append(handlerId);
            if (CallbackDispatcher.handlerNeedsState(handlerId)) {
                sb.append(", ").append(CallbackSourceBuilder.quote(stateKey));
            }
            sb.append(", ").append(selfExpr)
              .append(", jffl_args, jffl_original, jffl_cir);");
            sb.append("$_ = jffl_cir.hasReturnValue() ? jffl_cir.getReturnValue() : $proceed();");
            sb.append("}");
        } else {
            sb.append("Object[] jffl_args = new Object[]{ ($w)$1 };");
            sb.append("java.util.concurrent.Callable jffl_original = new java.util.concurrent.Callable() {")
              .append("  public Object call() throws Exception {$proceed($1); return null;}")
              .append("};");
            sb.append(CallbackInfoReturnable.class.getName())
              .append(" jffl_cir = new ").append(CallbackInfoReturnable.class.getName())
              .append("(\"").append(fa.getFieldName()).append("\", true);");
            sb.append(CallbackDispatcher.class.getName())
              .append(CallbackDispatcher.handlerNeedsState(handlerId) ? ".dispatchWrapState(" : ".dispatchWrap(")
              .append(handlerId);
            if (CallbackDispatcher.handlerNeedsState(handlerId)) {
                sb.append(", ").append(CallbackSourceBuilder.quote(stateKey));
            }
            sb.append(", ").append(selfExpr).append(", jffl_args, jffl_original, jffl_cir);");
            sb.append("}");
        }
        return sb.toString();
    }
}
