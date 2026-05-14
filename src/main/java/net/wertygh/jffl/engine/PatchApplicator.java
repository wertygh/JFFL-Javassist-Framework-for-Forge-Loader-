package net.wertygh.jffl.engine;

import net.wertygh.jffl.api.*;
import net.wertygh.jffl.api.annotation.*;
import net.wertygh.jffl.registry.PatchRegistry;
import javassist.*;
import javassist.bytecode.AccessFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArrayList;

public class PatchApplicator {
    private static Logger LOGGER = LoggerFactory.getLogger(PatchApplicator.class);
    public ClassPool classPool;

    public PatchApplicator(ClassPool classPool) {
        this.classPool = classPool;
    }

    public void applyPatch(CtClass ctClass, PatchRegistry.PatchEntry entry, PatchContext ctx) throws Exception {
        IClassPatch patch = entry.patch;
        String patchOwner = entry.displayName();
        Class<?> patchClass = patch.getClass();
        Patch[] patchAnns = patchClass.getAnnotationsByType(Patch.class);
        boolean classOptional = patchAnns.length > 0 && firstOptional(patchClass);
        if (!ConditionEvaluator.shouldApply(patchClass, classPool, ctClass)) return;
        installPatchStateFields(ctClass, patchClass, ctx, patchOwner);

        for (AddInterface ai : patchClass.getAnnotationsByType(AddInterface.class)) {
            try {
                CtClass iface = classPool.get(ai.value());
                if (hasInterface(ctClass, iface.getName())) {
                    ctx.warn("重复的@AddInterface" + iface.getName() + ", " + patchOwner);
                    continue;
                }
                ctClass.addInterface(iface);
            } catch (NotFoundException e) {
                if (classOptional) LOGGER.warn("未找到可选的@AddInterface{}", ai.value());
                else throw e;
            }
        }
        for (AddField af : patchClass.getAnnotationsByType(AddField.class)) {
            CtClass fieldType = EngineUtils.resolveType(classPool, af.type());
            if (EngineUtils.hasField(ctClass, af.name())) {
                try {
                    CtField existing = ctClass.getDeclaredField(af.name());
                    String existingType = existing.getType().getName();
                    if (!existingType.equals(fieldType.getName())) {
                        ctx.warn("字段" + af.name() + "已存在且类型不一致(" + existingType + " != " + fieldType.getName() + "), " + patchOwner);
                    } else {
                        ctx.warn("@AddField" + af.name() + ", " + patchOwner);
                    }
                } catch (NotFoundException ignored) {
                    ctx.warn("字段" + af.name() + "已存在, " + patchOwner);
                }
                continue;
            }
            CtField field = new CtField(fieldType, af.name(), ctClass);
            field.setModifiers(af.access() == 0 ? Modifier.PRIVATE : af.access());
            if (af.initializer().isEmpty()) ctClass.addField(field);
            else ctClass.addField(field, CtField.Initializer.byExpr(af.initializer()));
        }

        for (Method m : patchClass.getDeclaredMethods()) {
            m.setAccessible(true);
            applyMethodAnnotations(ctClass, patch, m, ctx, classOptional, patchOwner);
        }

        if (patch instanceof RawJavassistPatch raw) {
            raw.transform(ctClass, ctx);
        }
    }

    private static boolean firstOptional(Class<?> patchClass) {
        for (Patch p : patchClass.getAnnotationsByType(Patch.class)) {
            if (p.optional()) return true;
        }
        return false;
    }

    private void applyMethodAnnotations(CtClass ctClass, IClassPatch patch, Method m, PatchContext ctx, boolean classOptional, String patchOwner) throws Exception {
        Accessor acc = m.getAnnotation(Accessor.class);
        if (acc != null) {
            try {
                StructuralMutator.generateAccessor(ctClass, acc);
            } catch (NotFoundException e) {
                if (acc.optional() || classOptional) {
                    LOGGER.warn("可选的@Accessor目标'{}'未在{}上找到", acc.target(), ctx.getClassName());
                } else throw e;
            }
        }
        for (Shadow sh : m.getAnnotationsByType(Shadow.class)) {
            try {
                StructuralMutator.generateShadow(ctClass, patch, m, sh);
            } catch (NotFoundException e) {
                if (sh.optional() || classOptional) {
                    LOGGER.warn("可选@Shadow'{}'在{}上未找到", sh.target(), ctx.getClassName());
                } else throw e;
            }
        }
        for (InsertBefore ib : m.getAnnotationsByType(InsertBefore.class)) {
            CtMethod target = EngineUtils.findMethod(ctClass, ib.method(), ib.desc());
            String src = CallbackSourceBuilder.resolveCallbackOrString(patch, m, ib.method(), ib.cancellable(), target);
            if (CallbackSourceBuilder.methodNeedsState(m)) {
                ensureStateCleanup(target, patch, m, ib.method(), ctx);
                src = CallbackSourceBuilder.wrapWithStateEnter(patch, m, ib.method(), src);
            }
            target.insertBefore(src);
        }
        for (InsertAfter ia : m.getAnnotationsByType(InsertAfter.class)) {
            CtMethod target = EngineUtils.findMethod(ctClass, ia.method(), ia.desc());
            String src = CallbackSourceBuilder.resolveCallbackOrString(patch, m, ia.method(), ia.cancellable(), target);
            target.insertAfter(src);
        }
        for (ReplaceMethod rm : m.getAnnotationsByType(ReplaceMethod.class)) {
            CtMethod target = EngineUtils.findMethod(ctClass, rm.method(), rm.desc());
            String claimKey = "replace:" + EngineUtils.methodKey(target);
            if (!ctx.claim(claimKey, patchOwner)) {
                ctx.warn("覆盖型@ReplaceMethod" + EngineUtils.methodKey(target)
                        + ", 它已经被" + ctx.ownerOf(claimKey) + "占用; " + patchOwner);
                continue;
            }
            String body;
            if (EngineUtils.isCallbackMethod(m)) {
                body = CallbackSourceBuilder.buildBodyReplacementCallback(
                    patch, m, rm.method(), target, target.getParameterTypes(), 
                    (target.getModifiers() & Modifier.STATIC) != 0
                );
            } else {
                body = "{" + m.invoke(patch) + "}";
            }
            target.setBody(body);
        }
        for (AddMethod am : m.getAnnotationsByType(AddMethod.class)) {
            CtClass returnType = EngineUtils.resolveType(classPool, am.returnType());
            CtClass[] paramTypes = EngineUtils.parseParamTypes(classPool, am.params());
            String methodDesc = EngineUtils.buildMethodDescriptor(returnType, paramTypes);
            if (EngineUtils.hasMethod(ctClass, am.name(), methodDesc)) {
                ctx.warn("重复的@AddMethod" + EngineUtils.methodKey(am.name(), methodDesc) + ", " + patchOwner);
                continue;
            }
            String body;
            if (EngineUtils.isCallbackMethod(m)) {
                boolean isStatic = (am.access() & Modifier.STATIC) != 0;
                body = CallbackSourceBuilder.buildAddMethodCallback(patch, m, am.name(), returnType, paramTypes, isStatic);
            } else {
                body = (String) m.invoke(patch);
            }
            String methodSrc = EngineUtils.buildMethodSource(am, body);
            CtMethod newMethod = CtNewMethod.make(methodSrc, ctClass);
            ctClass.addMethod(newMethod);
        }
        for (Inject inj : m.getAnnotationsByType(Inject.class)) {
            try {
                CtMethod target = EngineUtils.findMethod(ctClass, inj.method(), inj.desc());
                String src = CallbackSourceBuilder.resolveCallbackOrString(patch, m, inj.method(), inj.cancellable(), target);
                if (CallbackSourceBuilder.methodNeedsState(m)) {
                    ensureStateCleanup(target, patch, m, inj.method(), ctx);
                    if (inj.at().value() == At.Value.HEAD) {
                        src = CallbackSourceBuilder.wrapWithStateEnter(patch, m, inj.method(), src);
                    }
                }
                Slice slice = inj.slice();
                InjectionPoints.apply(target, inj.at(), src, slice);
            } catch (NotFoundException e) {
                if (inj.optional() || classOptional) {
                    LOGGER.warn("可选的@Inject目标{}.{}未找到", ctx.getClassName(), inj.method());
                } else throw e;
            }
        }
        for (Redirect rd : m.getAnnotationsByType(Redirect.class)) {
            try {
                CtMethod target = EngineUtils.findMethod(ctClass, rd.method(), rd.desc());
                String src = CallbackSourceBuilder.resolveCallbackOrString(patch, m, rd.method(), rd.cancellable(), target);
                Slice slice = rd.slice();
                InjectionPoints.applyRedirect(target, rd.at(), src, slice);
            } catch (NotFoundException e) {
                if (rd.optional() || classOptional) {
                    LOGGER.warn("可选的@Redirect目标{}.{}未找到", ctx.getClassName(), rd.method());
                } else throw e;
            }
        }
        for (WrapOperation wop : m.getAnnotationsByType(WrapOperation.class)) {
            try {
                ExprInstrumenter.applyWrapOperation(ctClass, patch, m, wop, ctx);
            } catch (NotFoundException e) {
                if (wop.optional() || classOptional) {
                    LOGGER.warn("可选@WrapOperation目标{}.{}未找到", ctx.getClassName(), wop.method());
                } else throw e;
            }
        }
        for (ModifyConstant mc : m.getAnnotationsByType(ModifyConstant.class)) {
            try {
                CtMethod target = EngineUtils.findMethod(ctClass, mc.method(), mc.desc());
                String replacement = CallbackSourceBuilder.resolveCallbackOrString(patch, m, mc.method(), mc.cancellable(), target);
                At synthetic = SyntheticAt.fromModifyConstant(mc);
                Slice slice = mc.slice();
                InjectionPoints.apply(target, synthetic, replacement, slice);
            } catch (NotFoundException e) {
                if (mc.optional() || classOptional) {
                    LOGGER.warn("可选的@ModifyConstant目标{}.{}未找到", ctx.getClassName(), mc.method());
                } else throw e;
            }
        }
        for (ModifyReturnValue mrv : m.getAnnotationsByType(ModifyReturnValue.class)) {
            CtMethod target = EngineUtils.findMethod(ctClass, mrv.method(), mrv.desc());
            String src;
            if (EngineUtils.isCallbackMethod(m)) {
                src = CallbackSourceBuilder.buildModifyReturnValueCallback(patch, m, mrv.method(), target);
            } else {
                src = "{" + m.invoke(patch) + "}";
            }
            target.insertAfter(src, false);
        }
        for (InsertAtLine ial : m.getAnnotationsByType(InsertAtLine.class)) {
            CtMethod target = EngineUtils.findMethod(ctClass, ial.method(), ial.desc());
            String src;
            if (EngineUtils.isCallbackMethod(m)) {
                src = CallbackSourceBuilder.resolveCallbackOrString(patch, m, ial.method(), false, target);
            } else {
                src = "{" + m.invoke(patch) + "}";
            }
            target.insertAt(ial.line(), src);
        }
        for (WrapTryCatch wtc : m.getAnnotationsByType(WrapTryCatch.class)) {
            CtMethod target = EngineUtils.findMethod(ctClass, wtc.method(), wtc.desc());
            CtClass exType = classPool.get(wtc.exceptionType());
            String catchBody;
            if (EngineUtils.isCallbackMethod(m)) {
                catchBody = CallbackSourceBuilder.buildTryCatchCallback(patch, m, wtc.method(), target, exType);
            } else {
                catchBody = "{" + m.invoke(patch) + "}";
            }
            target.addCatch(catchBody, exType);
        }
        for (AddConstructorCode acc2 : m.getAnnotationsByType(AddConstructorCode.class)) {
            CtConstructor[] ctors;
            if (acc2.desc().isEmpty()) {
                ctors = ctClass.getDeclaredConstructors();
            } else {
                ctors = new CtConstructor[]{ ctClass.getConstructor(acc2.desc()) };
            }
            for (CtConstructor ctor : ctors) {
                String src;
                if (EngineUtils.isCallbackMethod(m)) {
                    src = CallbackSourceBuilder.buildConstructorCallback(patch, m, "<init>", ctor);
                } else {
                    src = "{" + m.invoke(patch) + "}";
                }
                switch (acc2.position()) {
                    case BEFORE -> ctor.insertBeforeBody(src);
                    case AFTER -> ctor.insertAfter(src);
                    case BEFORE_SUPER -> StructuralMutator.insertBeforeSuperCall(ctor, EngineUtils.stripOuterBraces(src));
                }
            }
        }
        for (InjectStaticInit isi : m.getAnnotationsByType(InjectStaticInit.class)) {
            String src;
            if (EngineUtils.isCallbackMethod(m)) {
                src = CallbackSourceBuilder.buildStaticInitCallback(patch, m, "<clinit>");
            } else {
                src = "{" + m.invoke(patch) + "}";
            }
            CtConstructor clinit = ctClass.getClassInitializer();
            if (clinit == null) {
                clinit = ctClass.makeClassInitializer();
            }
            if (isi.position() == InjectStaticInit.Position.BEFORE) {
                clinit.insertBefore(src);
            } else {
                clinit.insertAfter(src);
            }
        }
        for (CloneMethod cm : m.getAnnotationsByType(CloneMethod.class)) {
            CtMethod target = EngineUtils.findMethod(ctClass, cm.method(), cm.desc());
            String cloneName = cm.cloneName().isEmpty()
                    ? "jffl$original$" + cm.method() : cm.cloneName();
            if (!EngineUtils.hasMethod(ctClass, cloneName, target.getMethodInfo().getDescriptor())) {
                CtMethod clone = CtNewMethod.copy(target, cloneName, ctClass, null);
                clone.setModifiers(clone.getModifiers() | AccessFlag.SYNTHETIC);
                ctClass.addMethod(clone);
            } else {
                ctx.warn("重复的@CloneMethod生成方法" + EngineUtils.methodKey(cloneName, target.getMethodInfo().getDescriptor()) + ", " + patchOwner);
            }
            String newBody;
            if (EngineUtils.isCallbackMethod(m)) {
                newBody = CallbackSourceBuilder.buildBodyReplacementCallback(
                    patch, m, cm.method(), target, target.getParameterTypes(), 
                    (target.getModifiers() & Modifier.STATIC) != 0
                );
            } else {
                String raw = (String) m.invoke(patch);
                newBody = (raw != null && !raw.isEmpty()) ? "{" + raw + "}" : null;
            }
            if (newBody != null && !newBody.isEmpty()) {
                target.setBody(newBody);
            }
        }
        for (WrapMethod wm : m.getAnnotationsByType(WrapMethod.class)) {
            ExprInstrumenter.applyWrapMethod(ctClass, patch, m, wm, ctx);
        }
        for (ModifyArg ma : m.getAnnotationsByType(ModifyArg.class)) {
            ExprInstrumenter.applyModifyArg(ctClass, patch, m, ma, ctx);
        }
        for (ModifyVariable mv : m.getAnnotationsByType(ModifyVariable.class)) {
            ExprInstrumenter.applyModifyVariable(ctClass, patch, m, mv, ctx);
        }
        for (InstrumentNewExpr ine : m.getAnnotationsByType(InstrumentNewExpr.class)) {
            ExprInstrumenter.applyInstrumentNewExpr(ctClass, patch, m, ine, ctx);
        }
        for (InstrumentCast ic : m.getAnnotationsByType(InstrumentCast.class)) {
            ExprInstrumenter.applyInstrumentCast(ctClass, patch, m, ic, ctx);
        }
        for (InstrumentInstanceof ii : m.getAnnotationsByType(InstrumentInstanceof.class)) {
            ExprInstrumenter.applyInstrumentInstanceof(ctClass, patch, m, ii, ctx);
        }
        for (InstrumentHandler ih : m.getAnnotationsByType(InstrumentHandler.class)) {
            ExprInstrumenter.applyInstrumentHandler(ctClass, patch, m, ih, ctx);
        }
        for (InstrumentFieldAccess ifa : m.getAnnotationsByType(InstrumentFieldAccess.class)) {
            ExprInstrumenter.applyInstrumentFieldAccess(ctClass, patch, m, ifa, ctx);
        }
        for (InstrumentConstructorCall icc : m.getAnnotationsByType(InstrumentConstructorCall.class)) {
            ExprInstrumenter.applyInstrumentConstructorCall(ctClass, patch, m, icc, ctx);
        }
    }

    private void installPatchStateFields(CtClass ctClass, Class<?> patchClass, PatchContext ctx, String patchOwner) throws Exception {
        for (Field reflected : patchClass.getDeclaredFields()) {
            PatchState state = reflected.getAnnotation(PatchState.class);
            if (state == null) continue;
            reflected.setAccessible(true);
            if (java.lang.reflect.Modifier.isStatic(reflected.getModifiers())) {
                tryInitializePatchState(reflected);
            } else {
                ctx.warn("@PatchState字段" + reflected.getName() + "不是static, 仅支持目标类字段注入; " + patchOwner);
            }
            if (!state.targetField()) continue;
            String fieldName = state.name().isEmpty()
                    ? "jffl$state$" + EngineUtils.safePatchId(patchClass.getName()) + "$" + reflected.getName()
                    : state.name();
            if (EngineUtils.hasField(ctClass, fieldName)) {
                continue;
            }
            CtClass fieldType = EngineUtils.resolveType(classPool, reflected.getType().getName());
            CtField field = new CtField(fieldType, fieldName, ctClass);
            int access = state.access() == 0 ? (Modifier.PRIVATE | Modifier.STATIC) : state.access();
            field.setModifiers(access);
            String initializer = state.initializer().trim();
            if (initializer.isEmpty()) initializer = defaultStateInitializer(reflected.getType());
            if (initializer.isEmpty()) {
                ctClass.addField(field);
            } else {
                ctClass.addField(field, CtField.Initializer.byExpr(initializer));
            }
        }
    }

    private static void tryInitializePatchState(Field field) {
        try {
            if (field.get(null) != null) return;
            Object value = defaultPatchStateValue(field.getType());
            if (value != null) field.set(null, value);
        } catch (Throwable t) {
            LOGGER.debug("初始化@PatchState字段{}失败: {}", field.getName(), t.getMessage());
        }
    }

    private static Object defaultPatchStateValue(Class<?> type) throws Exception {
        if (type == Map.class || type == ConcurrentMap.class || type == ConcurrentHashMap.class) {
            return new ConcurrentHashMap<>();
        }
        if (type == List.class) return new CopyOnWriteArrayList<>();
        if (type == Set.class) return ConcurrentHashMap.newKeySet();
        if (type.isInterface() || type.isPrimitive()) return null;
        var ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static String defaultStateInitializer(Class<?> type) {
        String name = type.getName();
        if (type == Map.class || type == ConcurrentMap.class || type == ConcurrentHashMap.class) {
            return "new java.util.concurrent.ConcurrentHashMap()";
        }
        if (type == List.class) return "new java.util.concurrent.CopyOnWriteArrayList()";
        if (type == Set.class) return "java.util.concurrent.ConcurrentHashMap.newKeySet()";
        if (!type.isInterface() && !type.isPrimitive()) return "new " + name + "()";
        return "";
    }

    private static void ensureStateCleanup(CtMethod target, IClassPatch patch, Method m, String methodName, PatchContext ctx) throws CannotCompileException {
        String key = "状态清理:" + target.getDeclaringClass().getName() 
                + ":" + EngineUtils.methodKey(target)
                + ":" + CallbackSourceBuilder.stateKey(patch, m, methodName);
        if (ctx.ownerOf(key) != null) return;
        ctx.claim(key, "jffl-state");
        target.insertAfter("{" + CallbackSourceBuilder.stateExitSource(patch, m, methodName) + "}", true);
    }

    private static boolean hasInterface(CtClass ctClass, String ifaceName) throws NotFoundException {
        for (CtClass iface : ctClass.getInterfaces()) {
            if (iface.getName().equals(ifaceName)) return true;
        }
        return false;
    }
}
