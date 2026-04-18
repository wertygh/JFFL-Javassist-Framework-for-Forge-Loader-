package net.wertygh.jffl.engine;

import net.wertygh.jffl.api.*;
import net.wertygh.jffl.api.annotation.*;
import net.wertygh.jffl.env.DevEnvironment;
import net.wertygh.jffl.registry.PatchRegistry;
import javassist.*;
import javassist.bytecode.*;
import javassist.expr.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class PatchEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(PatchEngine.class);
    private static final boolean DEV_DUMP = DevEnvironment.isDev()
            && "true".equalsIgnoreCase(System.getProperty("jffl.dev.dump"));
    private final PatchRegistry registry;
    private final ClassPool classPool;
    private final AtomicBoolean mappingsLoadAttempted = new AtomicBoolean(false);
    private volatile boolean autoLoadMappings = true;

    public PatchEngine(PatchRegistry registry) {
        this.registry = registry;
        this.classPool = new ClassPool(true);
        this.classPool.appendClassPath(new LoaderClassPath(Thread.currentThread().getContextClassLoader()));
    }

    public PatchEngine(PatchRegistry registry, ClassLoader parentLoader) {
        this.registry = registry;
        this.classPool = new ClassPool(true);
        if (parentLoader != null) {
            this.classPool.appendClassPath(new LoaderClassPath(parentLoader));
        }
    }

    public void appendClassPath(String path) {
        try {
            classPool.appendClassPath(path);
        } catch (NotFoundException e) {
            LOGGER.warn("无法添加classpath: {}", path, e);
        }
    }
    
    public ClassPool getClassPool() {
        return classPool;
    }
    
    public void loadMappings(Path path) {
        MappingLoader.loadFromFile(path);
    }
    
    public void loadMappings(File file) {
        MappingLoader.loadFromFile(file);
    }
    
    public void loadMappings(InputStream inputStream) {
        MappingLoader.loadFromStream(inputStream);
    }
    
    public void loadMappingsFromResource(String resourcePath) {
        MappingLoader.loadFromResource(resourcePath);
    }
    
    public void autoDiscoverMappings() {
        MappingLoader.autoDiscover();
    }
    
    public void setAutoLoadMappings(boolean autoLoad) {
        this.autoLoadMappings = autoLoad;
    }
    
    private static boolean isJavassistInternal(String className) {
        if (className == null) return false;
        String n = className.replace('/', '.');
        return n.startsWith("javassist.");
    }

    public byte[] transform(String className, byte[] classBytes) {
        if (autoLoadMappings
                && DevEnvironment.isProduction()
                && mappingsLoadAttempted.compareAndSet(false, true)
                && !isJavassistInternal(className)) {
            try {
                MappingLoader.autoDiscover();
            } catch (Throwable t) {
                LOGGER.warn("自动发现SRG映射失败", t);
            }
        }
        List<PatchRegistry.PatchEntry> patches = registry.getPatches(className);
        if (patches.isEmpty()) return classBytes;
        if (DevEnvironment.isDev()) {
            LOGGER.debug("[DEV] 正在转换类: {}({}个补丁)", className, patches.size());
        }
        Set<String> mixinTargets = registry.getMixinTargets();
        if (!mixinTargets.isEmpty()) {
            String dottedName = className.replace('/', '.');
            if (mixinTargets.contains(dottedName)) {
                LOGGER.warn("类'{}'正被JFFL修补, 但同时也是Mixin的目标, 这可能会导致冲突.", className);
            }
        }
        boolean shouldDump = DEV_DUMP;
        String dumpDir = ".jffl-dump";
        try {
            CtClass ctClass = classPool.makeClass(new ByteArrayInputStream(classBytes), false);
            try {
                PatchContext ctx = new PatchContext(className, classPool);
                for (PatchRegistry.PatchEntry entry : patches) {
                    try {
                        if (DevEnvironment.isDev()) {
                            LOGGER.debug("[DEV] 应用补丁: {} → {} (优先级 {})",
                                    entry.patch().getClass().getSimpleName(), className, entry.priority());
                        }
                        applyPatch(ctClass, entry.patch(), ctx);
                        DumpClass dc = entry.patch().getClass().getAnnotation(DumpClass.class);
                        if (dc != null) {
                            shouldDump = true;
                            dumpDir = dc.dir();
                        }
                    } catch (Exception e) {
                        LOGGER.error("补丁{}在类{}上失败",
                                entry.patch().getClass().getSimpleName(), className, e);
                        if (DevEnvironment.isDev()) {
                            LOGGER.error("[DEV] 补丁失败的详细堆栈:", e);
                        }
                    }
                }
                byte[] result = ctClass.toBytecode();
                if (shouldDump) {
                    dumpClassBytes(className, result, dumpDir);
                }
                return result;
            } finally {
                ctClass.detach();
            }
        } catch (Exception e) {
            LOGGER.error("类{}的PatchEngine失败", className, e);
            return classBytes;
        }
    }

    private void dumpClassBytes(String className, byte[] bytes, String dir) {
        try {
            String dottedName = className.replace('/', '.');
            File outFile = new File(dir, dottedName + ".class");
            outFile.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(bytes);
            }
            LOGGER.info("@DumpClass: 写入{}({}字节)", outFile.getPath(), bytes.length);
        } catch (Exception e) {
            LOGGER.warn("@DumpClass: 转储{}失败: {}", className, e.getMessage());
        }
    }

    private void applyPatch(CtClass ctClass, IClassPatch patch, PatchContext ctx) throws Exception {
        Class<?> patchClass = patch.getClass();
        boolean classOptional = patchClass.isAnnotationPresent(Patch.class)
                && patchClass.getAnnotation(Patch.class).optional();
        if (!ConditionEvaluator.shouldApply(patchClass, classPool, ctClass)) return;
        for (AddInterface ai : patchClass.getAnnotationsByType(AddInterface.class)) {
            try {
                CtClass iface = classPool.get(ai.value());
                ctClass.addInterface(iface);
            } catch (NotFoundException e) {
                if (classOptional) LOGGER.warn("未找到可选的@AddInterface{}", ai.value());
                else throw e;
            }
        }

        for (AddField af : patchClass.getAnnotationsByType(AddField.class)) {
            CtClass fieldType = resolveType(af.type());
            CtField field = new CtField(fieldType, af.name(), ctClass);
            field.setModifiers(af.access() == 0 ? Modifier.PRIVATE : af.access());
            if (af.initializer().isEmpty()) {
                ctClass.addField(field);
            } else {
                ctClass.addField(field, CtField.Initializer.byExpr(af.initializer()));
            }
        }

        for (Method m : patchClass.getDeclaredMethods()) {
            m.setAccessible(true);
            applyMethodAnnotations(ctClass, patch, m, ctx, classOptional);
        }

        if (patch instanceof RawJavassistPatch raw) {
            raw.transform(ctClass, ctx);
        }
    }

    private void applyMethodAnnotations(CtClass ctClass, IClassPatch patch, Method m, PatchContext ctx, boolean classOptional) throws Exception {
        Accessor acc = m.getAnnotation(Accessor.class);
        if (acc != null) {
            try {
                generateAccessor(ctClass, acc);
            } catch (NotFoundException e) {
                if (acc.optional() || classOptional) {
                    LOGGER.warn("可选的@Accessor目标'{}'未在{}上找到",
                            acc.target(), ctx.getClassName());
                } else throw e;
            }
        }

        for (InsertBefore ib : m.getAnnotationsByType(InsertBefore.class)) {
            CtMethod target = findMethod(ctClass, ib.method(), ib.desc());
            String src = resolveCallbackOrString(patch, m, ib.method(), ib.cancellable(), target);
            target.insertBefore(src);
        }

        for (InsertAfter ia : m.getAnnotationsByType(InsertAfter.class)) {
            CtMethod target = findMethod(ctClass, ia.method(), ia.desc());
            String src = resolveCallbackOrString(patch, m, ia.method(), ia.cancellable(), target);
            target.insertAfter(src);
        }

        for (ReplaceMethod rm : m.getAnnotationsByType(ReplaceMethod.class)) {
            CtMethod target = findMethod(ctClass, rm.method(), rm.desc());
            String body;
            if (isCallbackMethod(m)) {
                body = buildBodyReplacementCallback(patch, m, rm.method(), target,
                        target.getParameterTypes(), (target.getModifiers() & Modifier.STATIC) != 0);
            } else {
                body = "{" + m.invoke(patch) + "}";
            }
            target.setBody(body);
        }

        for (AddMethod am : m.getAnnotationsByType(AddMethod.class)) {
            String body;
            if (isCallbackMethod(m)) {
                CtClass returnType = resolveType(am.returnType());
                CtClass[] paramTypes = parseParamTypes(am.params());
                boolean isStatic = (am.access() & Modifier.STATIC) != 0;
                body = buildAddMethodCallback(patch, m, am.name(), returnType, paramTypes, isStatic);
            } else {
                body = (String) m.invoke(patch);
            }
            String methodSrc = buildMethodSource(am, body);
            CtMethod newMethod = CtNewMethod.make(methodSrc, ctClass);
            ctClass.addMethod(newMethod);
        }

        for (Inject inj : m.getAnnotationsByType(Inject.class)) {
            try {
                CtMethod target = findMethod(ctClass, inj.method(), inj.desc());
                String src = resolveCallbackOrString(patch, m, inj.method(), inj.cancellable(), target);
                Slice slice = inj.slice();
                InjectionPoints.apply(target, inj.at(), src, slice);
            } catch (NotFoundException e) {
                if (inj.optional() || classOptional) {
                    LOGGER.warn("可选的@Inject目标 {}.{}未找到",
                            ctx.getClassName(), inj.method());
                } else throw e;
            }
        }

        for (Redirect rd : m.getAnnotationsByType(Redirect.class)) {
            try {
                CtMethod target = findMethod(ctClass, rd.method(), rd.desc());
                String src = resolveCallbackOrString(patch, m, rd.method(), rd.cancellable(), target);
                Slice slice = rd.slice();
                InjectionPoints.applyRedirect(target, rd.at(), src, slice);
            } catch (NotFoundException e) {
                if (rd.optional() || classOptional) {
                    LOGGER.warn("可选的@Redirect目标{}.{}未找到",
                            ctx.getClassName(), rd.method());
                } else throw e;
            }
        }

        for (ModifyConstant mc : m.getAnnotationsByType(ModifyConstant.class)) {
            try {
                CtMethod target = findMethod(ctClass, mc.method(), mc.desc());
                String replacement = resolveCallbackOrString(patch, m, mc.method(), mc.cancellable(), target);
                At synthetic = SyntheticAt.fromModifyConstant(mc);
                Slice slice = mc.slice();
                InjectionPoints.apply(target, synthetic, replacement, slice);
            } catch (NotFoundException e) {
                if (mc.optional() || classOptional) {
                    LOGGER.warn("可选的@ModifyConstant目标{}.{}未找到",
                            ctx.getClassName(), mc.method());
                } else throw e;
            }
        }
        for (ModifyReturnValue mrv : m.getAnnotationsByType(ModifyReturnValue.class)) {
            CtMethod target = findMethod(ctClass, mrv.method(), mrv.desc());
            String src;
            if (isCallbackMethod(m)) {
                src = buildModifyReturnValueCallback(patch, m, mrv.method(), target);
            } else {
                src = "{" + m.invoke(patch) + "}";
            }
            target.insertAfter(src, false);
        }
        for (InsertAtLine ial : m.getAnnotationsByType(InsertAtLine.class)) {
            CtMethod target = findMethod(ctClass, ial.method(), ial.desc());
            String src;
            if (isCallbackMethod(m)) {
                src = resolveCallbackOrString(patch, m, ial.method(), false, target);
            } else {
                src = "{" + m.invoke(patch) + "}";
            }
            target.insertAt(ial.line(), src);
        }
        for (WrapTryCatch wtc : m.getAnnotationsByType(WrapTryCatch.class)) {
            CtMethod target = findMethod(ctClass, wtc.method(), wtc.desc());
            CtClass exType = classPool.get(wtc.exceptionType());
            String catchBody;
            if (isCallbackMethod(m)) {
                catchBody = buildTryCatchCallback(patch, m, wtc.method(), target, exType);
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
                if (isCallbackMethod(m)) {
                    src = buildConstructorCallback(patch, m, "<init>", ctor);
                } else {
                    src = "{" + m.invoke(patch) + "}";
                }
                switch (acc2.position()) {
                    case BEFORE -> ctor.insertBeforeBody(src);
                    case AFTER -> ctor.insertAfter(src);
                    case BEFORE_SUPER -> insertBeforeSuperCall(ctor, stripOuterBraces(src));
                }
            }
        }
        for (InjectStaticInit isi : m.getAnnotationsByType(InjectStaticInit.class)) {
            String src;
            if (isCallbackMethod(m)) {
                src = buildStaticInitCallback(patch, m, "<clinit>");
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
            CtMethod target = findMethod(ctClass, cm.method(), cm.desc());
            String cloneName = cm.cloneName().isEmpty()
                    ? "jffl$original$" + cm.method() : cm.cloneName();
            CtMethod clone = CtNewMethod.copy(target, cloneName, ctClass, null);
            clone.setModifiers(clone.getModifiers() | AccessFlag.SYNTHETIC);
            if (!hasMethod(ctClass, cloneName)) {
                ctClass.addMethod(clone);
            }
            String newBody;
            if (isCallbackMethod(m)) {
                newBody = buildBodyReplacementCallback(patch, m, cm.method(), target,
                        target.getParameterTypes(), (target.getModifiers() & Modifier.STATIC) != 0);
            } else {
                String raw = (String) m.invoke(patch);
                newBody = (raw != null && !raw.isEmpty()) ? "{" + raw + "}" : null;
            }
            if (newBody != null && !newBody.isEmpty()) {
                target.setBody(newBody);
            }
        }
        for (WrapMethod wm : m.getAnnotationsByType(WrapMethod.class)) {
            applyWrapMethod(ctClass, patch, m, wm, ctx);
        }
        for (ModifyArg ma : m.getAnnotationsByType(ModifyArg.class)) {
            applyModifyArg(ctClass, patch, m, ma, ctx);
        }
        for (ModifyVariable mv : m.getAnnotationsByType(ModifyVariable.class)) {
            applyModifyVariable(ctClass, patch, m, mv, ctx);
        }
        for (InstrumentNewExpr ine : m.getAnnotationsByType(InstrumentNewExpr.class)) {
            applyInstrumentNewExpr(ctClass, patch, m, ine, ctx);
        }
        for (InstrumentCast ic : m.getAnnotationsByType(InstrumentCast.class)) {
            applyInstrumentCast(ctClass, patch, m, ic, ctx);
        }
        for (InstrumentInstanceof ii:m.getAnnotationsByType(InstrumentInstanceof.class)) {
            applyInstrumentInstanceof(ctClass, patch, m, ii, ctx);
        }
        for (InstrumentHandler ih : m.getAnnotationsByType(InstrumentHandler.class)) {
            applyInstrumentHandler(ctClass, patch, m, ih, ctx);
        }
        for (InstrumentFieldAccess ifa : m.getAnnotationsByType(InstrumentFieldAccess.class)) {
            applyInstrumentFieldAccess(ctClass, patch, m, ifa, ctx);
        }
        for (InstrumentConstructorCall icc : m.getAnnotationsByType(InstrumentConstructorCall.class)) {
            applyInstrumentConstructorCall(ctClass, patch, m, icc, ctx);
        }
    }

    private void applyInstrumentInstanceof(CtClass ctClass, IClassPatch patch, Method handlerMethod, InstrumentInstanceof ann, PatchContext ctx) throws Exception {
        CtMethod target = findMethod(ctClass, ann.method(), ann.desc());
        final boolean callback = isCallbackMethod(handlerMethod);
        final String replacement = callback ? null : (String) handlerMethod.invoke(patch);
        final String callbackReplacement = callback
                ? buildExprValueCallback(patch, handlerMethod, ann.method(), "boolean",
                        new String[]{"$1"}, "boolean")
                : null;
        final String targetType = ann.target();
        final int wantOrdinal = ann.ordinal();
        final int[] seen = {0};
        target.instrument(new ExprEditor() {
            @Override
            public void edit(Instanceof i) throws CannotCompileException {
                try {
                    if (!targetType.isEmpty() 
                            && !i.getType().getName().equals(targetType)) return;
                } catch (NotFoundException e) {return;}
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                i.replace(callback ? callbackReplacement : "{" + replacement + "}");
            }
        });
    }

    private void applyInstrumentHandler(CtClass ctClass, IClassPatch patch, Method handlerMethod, InstrumentHandler ann, PatchContext ctx) throws Exception {
        CtMethod target = findMethod(ctClass, ann.method(), ann.desc());
        final boolean callback = isCallbackMethod(handlerMethod);
        final String replacement = callback
                ? buildExprInsertionCallback(patch, handlerMethod, ann.method(), new String[]{"$1"})
                : (String) handlerMethod.invoke(patch);
        final String exType = ann.exceptionType();
        final int wantOrdinal = ann.ordinal();
        final int[] seen = {0};
        target.instrument(new ExprEditor() {
            @Override
            public void edit(Handler h) throws CannotCompileException {
                try {
                    if (!exType.isEmpty()) {
                        CtClass caught = h.getType();
                        if (caught == null || !caught.getName().equals(exType)) return;
                    }
                } catch (NotFoundException e) {return;}
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                h.insertBefore(callback ? replacement : "{" + replacement + "}");
            }
        });
    }

    private void applyInstrumentFieldAccess(CtClass ctClass, IClassPatch patch, Method handlerMethod, InstrumentFieldAccess ann, PatchContext ctx) throws Exception {
        CtMethod target = findMethod(ctClass, ann.method(), ann.desc());
        final boolean callback = isCallbackMethod(handlerMethod);
        final String replacement = callback ? null : (String) handlerMethod.invoke(patch);
        final String fieldTarget = ann.target();
        final InstrumentFieldAccess.AccessType accessType = ann.accessType();
        final int wantOrdinal = ann.ordinal();
        final int[] seen = {0};
        target.instrument(new ExprEditor() {
            @Override
            public void edit(FieldAccess fa) throws CannotCompileException {
                if (accessType == InstrumentFieldAccess.AccessType.READ && fa.isWriter()) return;
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
                        String src = buildExprValueCallback(patch, handlerMethod, ann.method(),
                                fieldType, new String[0], fieldType);
                        fa.replace(src);
                    } else {
                        String src = buildFieldWriteCallback(patch, handlerMethod, ann.method(), fieldType);
                        fa.replace(src);
                    }
                } else {
                    fa.replace("{" + replacement + "}");
                }
            }
        });
    }

    private void applyInstrumentConstructorCall(CtClass ctClass, IClassPatch patch, Method handlerMethod, InstrumentConstructorCall ann, PatchContext ctx) throws Exception {
        CtMethod target = findMethod(ctClass, ann.method(), ann.desc());
        final boolean callback = isCallbackMethod(handlerMethod);
        final String replacement = callback ? null : (String) handlerMethod.invoke(patch);
        final String targetType = ann.target();
        final int wantOrdinal = ann.ordinal();
        final int[] seen = {0};
        target.instrument(new ExprEditor() {
            @Override
            public void edit(ConstructorCall c) throws CannotCompileException {
                if (!targetType.isEmpty() && !c.getClassName().equals(targetType)
                        && !c.getClassName().replace('.', '/')
                                .equals(targetType.replace('.', '/'))) return;
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                if (callback) {
                    String dispatchSrc = buildExprInsertionCallback(patch, handlerMethod, ann.method(), null);
                    c.replace("{" + stripOuterBraces(dispatchSrc) + " $proceed($$); }");
                } else {
                    c.replace("{" + replacement + "}");
                }
            }
            @Override
            public void edit(NewExpr e) throws CannotCompileException {
                if (!targetType.isEmpty() && !e.getClassName().equals(targetType)
                        && !e.getClassName().replace('.', '/')
                                .equals(targetType.replace('.', '/'))) return;
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                if (callback) {
                    String typeName = e.getClassName();
                    String src = buildExprValueCallback(patch, handlerMethod, ann.method(),
                            typeName, null, typeName);
                    e.replace(src);
                } else {
                    e.replace("{" + replacement + "}");
                }
            }
        });
    }

    private void applyWrapMethod(CtClass ctClass, IClassPatch patch, Method handlerMethod, WrapMethod wm, PatchContext ctx) throws Exception {
        CtMethod target = findMethod(ctClass, wm.method(), wm.desc());
        String wrappedName = "jffl$wrapped$" + wm.method();
        if (!hasMethod(ctClass, wrappedName)) {
            CtMethod clone = CtNewMethod.copy(target, wrappedName, ctClass, null);
            clone.setModifiers((clone.getModifiers() & ~Modifier.PRIVATE) | Modifier.PUBLIC | AccessFlag.SYNTHETIC);
            ctClass.addMethod(clone);
        }
        int id = CallbackDispatcher.register(patch, handlerMethod, true);
        CtClass returnType = target.getReturnType();
        CtClass[] paramTypes = target.getParameterTypes();
        StringBuilder sb = new StringBuilder("{");
        sb.append("Object[] jffl_args = new Object[")
          .append(paramTypes.length).append("];");
        for (int i = 0; i < paramTypes.length; i++) {
            sb.append("jffl_args[").append(i)
            .append("] = ($w)$").append(i + 1).append(";");
        }
        sb.append("java.util.concurrent.Callable jffl_original = ")
          .append(CallbackDispatcher.class.getName())
          .append(".createOriginalCaller($0, \"")
          .append(wrappedName).append("\", jffl_args);");
        sb.append(CallbackInfoReturnable.class.getName())
          .append(" jffl_cir = new ").append(CallbackInfoReturnable.class.getName())
          .append("(\"").append(wm.method()).append("\", true);");
        sb.append(CallbackDispatcher.class.getName()).append(".dispatchWrap(").append(id)
          .append(", $0, jffl_args, jffl_original, jffl_cir);");
        if (returnType == CtClass.voidType) {
            sb.append("}");
        } else {
            appendReturnCast(sb, returnType);
            sb.append("}");
        }
        target.setBody(sb.toString());
    }

    private void appendArgCasts(StringBuilder sb, CtClass[] paramTypes) throws NotFoundException {
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            CtClass pt = paramTypes[i];
            if (pt.isPrimitive()) {
                String unboxType = getUnboxExpression(pt);
                sb.append("((").append(getBoxedName(pt))
                  .append(")jffl_capturedArgs[").append(i)
                  .append("]).").append(unboxType);
            } else {
                sb.append("(").append(pt.getName())
                  .append(")jffl_capturedArgs[").append(i).append("]");
            }
        }
    }

    private void applyModifyArg(CtClass ctClass, IClassPatch patch, Method handlerMethod, ModifyArg ma, PatchContext ctx) throws Exception {
        CtMethod target = findMethod(ctClass, ma.method(), ma.desc());
        final boolean callback = isCallbackMethod(handlerMethod);
        final String replacement = callback ? null : (String) handlerMethod.invoke(patch);
        final int argIndex = ma.index();
        final String invokeTarget = ma.target();
        final int wantOrdinal = ma.ordinal();
        final int[] seen = {0};
        target.instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall mc) throws CannotCompileException {
                if (!invokeTarget.isEmpty() && !mc.getMethodName().equals(invokeTarget)
                        && !matchesFull(mc, invokeTarget)) return;
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                String argRef = "$" + (argIndex + 1);
                if (callback) {
                    String argType;
                    try {
                        CtClass[] ptypes = mc.getMethod().getParameterTypes();
                        argType = (argIndex < ptypes.length) ? ptypes[argIndex].getName() : "java.lang.Object";
                    } catch (NotFoundException e) {
                        argType = "java.lang.Object";
                    }
                    String body = buildModifyArgCallback(patch, handlerMethod, ma.method(),
                            argRef, argType);
                    mc.replace(body);
                } else {
                    String body = "{ " + argRef + " = " + replacement + "; $_ = $proceed($$); }";
                    mc.replace(body);
                }
            }
        });
    }

    private void applyModifyVariable(CtClass ctClass, IClassPatch patch, Method handlerMethod, ModifyVariable mv, PatchContext ctx) throws Exception {
        CtMethod target = findMethod(ctClass, mv.method(), mv.desc());
        final boolean callback = isCallbackMethod(handlerMethod);
        if (mv.line() > 0) {
            String modification = callback
                    ? buildModifyVariableLineCallback(patch, handlerMethod, mv.method())
                    : "{" + (String) handlerMethod.invoke(patch) + "}";
            target.insertAt(mv.line(), modification);
        } else if (mv.index() >= 0) {
            String varRef = "$" + mv.index();
            String src;
            if (callback) {
                src = buildModifyVariableCallback(patch, handlerMethod, mv.method(), varRef, "java.lang.Object");
            } else {
                src = "{ " + varRef + " = " + (String) handlerMethod.invoke(patch) + "; }";
            }
            InjectionPoints.apply(target, mv.at(), src);
        } else if (!mv.name().isEmpty()) {
            String src;
            if (callback) {
                src = buildModifyVariableCallback(patch, handlerMethod, mv.method(), mv.name(), "java.lang.Object");
            } else {
                src = "{ " + mv.name() + " = " + (String) handlerMethod.invoke(patch) + "; }";
            }
            InjectionPoints.apply(target, mv.at(), src);
        }
    }

    private void applyInstrumentNewExpr(CtClass ctClass, IClassPatch patch, Method handlerMethod, InstrumentNewExpr ine, PatchContext ctx) throws Exception {
        CtMethod target = findMethod(ctClass, ine.method(), ine.desc());
        final boolean callback = isCallbackMethod(handlerMethod);
        final String replacement = callback ? null : (String) handlerMethod.invoke(patch);
        final String targetType = ine.target().replace('.', '/');
        final int wantOrdinal = ine.ordinal();
        final int[] seen = {0};
        target.instrument(new ExprEditor() {
            @Override
            public void edit(NewExpr e) throws CannotCompileException {
                if (!targetType.isEmpty() 
                        && !e.getClassName().replace('.', '/').equals(targetType)
                        && !e.getClassName().equals(ine.target())) return;
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                if (callback) {
                    String typeName = e.getClassName();
                    String src = buildExprValueCallback(patch, handlerMethod, ine.method(),
                            typeName, null, typeName);
                    e.replace(src);
                } else {
                    e.replace("{" + replacement + "}");
                }
            }
        });
    }

    private void applyInstrumentCast(CtClass ctClass, IClassPatch patch, Method handlerMethod, InstrumentCast ic, PatchContext ctx) throws Exception {
        CtMethod target = findMethod(ctClass, ic.method(), ic.desc());
        final boolean callback = isCallbackMethod(handlerMethod);
        final String replacement = callback ? null : (String) handlerMethod.invoke(patch);
        final String targetType = ic.target();
        final int wantOrdinal = ic.ordinal();
        final int[] seen = {0};
        target.instrument(new ExprEditor() {
            @Override
            public void edit(Cast c) throws CannotCompileException {
                String typeName;
                try {
                    typeName = c.getType().getName();
                    if (!targetType.isEmpty() && !typeName.equals(targetType)) return;
                } catch (NotFoundException e) {return;}
                int idx = seen[0]++;
                if (wantOrdinal >= 0 && idx != wantOrdinal) return;
                if (callback) {
                    String src = buildExprValueCallback(patch, handlerMethod, ic.method(),
                            typeName, new String[]{"$1"}, typeName);
                    c.replace(src);
                } else {
                    c.replace("{" + replacement + "}");
                }
            }
        });
    }

    private String resolveCallbackOrString(IClassPatch patch, Method m, String methodName, boolean cancellable, CtMethod target) throws Exception {
        Class<?>[] params = m.getParameterTypes();
        boolean callbackMode = m.getReturnType() == void.class
                && params.length >= 1
                && (CallbackInfo.class.isAssignableFrom(lastParam(m)));
        if (!callbackMode) {return "{" + m.invoke(patch) + "}";}
        boolean returnable = CallbackInfoReturnable.class.isAssignableFrom(lastParam(m));
        boolean acceptsArgs = params.length >= 3
                && params[0] == Object.class
                && params[1] == Object[].class;
        int id = CallbackDispatcher.register(patch, m, returnable);
        CtClass[] targetParams = target.getParameterTypes();
        String ciExpr = returnable
                ? "new " + CallbackInfoReturnable.class.getName()
                        + "(\"" + methodName + "\", " + cancellable + ")"
                : "new " + CallbackInfo.class.getName()
                        + "(\"" + methodName + "\", " + cancellable + ")";
        StringBuilder sb = new StringBuilder("{");
        sb.append(CallbackInfo.class.getName())
          .append(" jffl_ci = ").append(ciExpr).append(";");
        if (acceptsArgs) {
            sb.append("Object[] jffl_args = new Object[")
              .append(targetParams.length).append("];");
            for (int i = 0; i < targetParams.length; i++) {
                sb.append("jffl_args[")
                  .append(i).append("] = ($w)$").append(i + 1).append(";");
            }
            if (returnable) {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnableWithArgs(").append(id)
                  .append(", $0, jffl_args, (")
                  .append(CallbackInfoReturnable.class.getName()).append(") jffl_ci);");
            } else {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchWithArgs(").append(id)
                  .append(", $0, jffl_args, jffl_ci);");
            }
        } else {
            if (returnable) {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnable(").append(id).append(", $0, (")
                  .append(CallbackInfoReturnable.class.getName()).append(") jffl_ci);");
            } else {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatch(").append(id).append(", $0, jffl_ci);");
            }
        }
        if (cancellable) {
            sb.append("if (jffl_ci.isCancelled()) {");
            try {
                CtClass rt = target.getReturnType();
                if (rt == CtClass.voidType) {
                    sb.append("return;");
                } else if (returnable) {
                    appendCancelReturn(sb, rt);
                } else {
                    sb.append(getDefaultReturnStatement(rt));
                }
            } catch (NotFoundException e) {
                sb.append("return;");
            }
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    private void appendCancelReturn(StringBuilder sb, CtClass returnType) {
        String cirCast = "((" + CallbackInfoReturnable.class.getName() + ") jffl_ci)";
        if (returnType.isPrimitive()) {
            String boxed = getBoxedName(returnType);
            String unbox = getUnboxExpression(returnType);
            sb.append("return ((").append(boxed).append(")").append(cirCast)
              .append(".getReturnValue()).").append(unbox).append(";");
        } else {
            sb.append("return (").append(returnType.getName()).append(")")
              .append(cirCast).append(".getReturnValue();");
        }
    }
    
    private static String getDefaultReturnStatement(CtClass rt) {
        if (rt == CtClass.booleanType) return "return false;";
        if (rt == CtClass.byteType || rt == CtClass.shortType ||
            rt == CtClass.intType || rt == CtClass.charType) return "return 0;";
        if (rt == CtClass.longType) return "return 0L;";
        if (rt == CtClass.floatType) return "return 0.0f;";
        if (rt == CtClass.doubleType) return "return 0.0;";
        return "return null;";
    }

    private void appendReturnCast(StringBuilder sb, CtClass returnType) {
        if (returnType.isPrimitive()) {
            String boxed = getBoxedName(returnType);
            String unbox = getUnboxExpression(returnType);
            sb.append("return ((").append(boxed)
              .append(") jffl_cir.getReturnValue()).").append(unbox).append(";");
        } else {
            sb.append("return (").append(returnType.getName())
              .append(") jffl_cir.getReturnValue();");
        }
    }

    private void insertBeforeSuperCall(CtConstructor ctor, String src) throws Exception {
        CtClass ctClass = ctor.getDeclaringClass();
        String helperName = "jffl$beforeSuper$" + System.identityHashCode(ctor);
        StringBuilder helperSrc = new StringBuilder();
        helperSrc.append("private static void ").append(helperName).append("(");
        CtClass[] paramTypes = ctor.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) helperSrc.append(", ");
            helperSrc.append(paramTypes[i].getName()).append(" arg").append(i);
        }
        helperSrc.append(") { ").append(src).append(" }");
        try {
            ctClass.addMethod(CtNewMethod.make(helperSrc.toString(), ctClass));
        } catch (Exception e) {
            LOGGER.warn("创建BEFORE_SUPER辅助方法失败：{}", e.getMessage());
            ctor.insertBeforeBody("{" + src + "}");
            return;
        }
        MethodInfo mi = ctor.getMethodInfo();
        CodeAttribute ca = mi.getCodeAttribute();
        if (ca != null) {
            CodeIterator it = ca.iterator();
            Bytecode bc = new Bytecode(mi.getConstPool());
            int slot = 1;
            for (int i = 0; i < paramTypes.length; i++) {
                addLoadInstruction(bc, paramTypes[i], slot);
                slot += (paramTypes[i] == CtClass.longType || paramTypes[i] == CtClass.doubleType) ? 2 : 1;
            }
            String desc = Descriptor.ofMethod(CtClass.voidType, paramTypes);
            bc.addInvokestatic(ctClass, helperName, desc);
            it.insert(0, bc.get());
            ca.computeMaxStack();
        }
    }

    private static void addLoadInstruction(Bytecode bc, CtClass type, int index) {
        if (type == CtClass.intType || type == CtClass.booleanType || type == CtClass.byteType || type == CtClass.charType || type == CtClass.shortType) {
            bc.addIload(index);
        } else if (type == CtClass.longType) {
            bc.addLload(index);
        } else if (type == CtClass.floatType) {
            bc.addFload(index);
        } else if (type == CtClass.doubleType) {
            bc.addDload(index);
        } else {
            bc.addAload(index);
        }
    }

    private static Class<?> lastParam(Method m) {
        Class<?>[] p = m.getParameterTypes();
        return p[p.length - 1];
    }

    private void generateAccessor(CtClass ctClass, Accessor acc) throws Exception {
        if (acc.invoker()) {
            CtMethod priv = !acc.desc().isEmpty()
                    ? findMethod(ctClass, acc.target(), acc.desc())
                    : ctClass.getDeclaredMethod(acc.target());
            String bridgeName = "jffl$invoke$" + acc.target();
            if (hasMethod(ctClass, bridgeName)) return;
            boolean isStatic = (priv.getModifiers() & Modifier.STATIC) != 0;
            StringBuilder sb = new StringBuilder("public ");
            if (isStatic) sb.append("static ");
            sb.append(priv.getReturnType().getName())
              .append(' ').append(bridgeName).append('(');
            CtClass[] params = priv.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getName()).append(" arg").append(i);
            }
            sb.append(") { ");
            if (priv.getReturnType() != CtClass.voidType) sb.append("return ");
            if (isStatic) sb.append(ctClass.getName()).append('.');
            sb.append(acc.target()).append('(');
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append("arg").append(i);
            }
            sb.append("); }");
            ctClass.addMethod(CtNewMethod.make(sb.toString(), ctClass));
        } else {
            CtField f = ctClass.getDeclaredField(acc.target());
            String getName = "jffl$get$" + acc.target();
            String setName = "jffl$set$" + acc.target();
            if (!hasMethod(ctClass, getName)) {
                ctClass.addMethod(CtNewMethod.make(
                        "public " + f.getType().getName() + " " + getName + "() { return this." + acc.target() + "; }",
                        ctClass));
            }
            if (!hasMethod(ctClass, setName)) {
                ctClass.addMethod(CtNewMethod.make(
                        "public void " + setName + "(" + f.getType().getName() + " v) { this." + acc.target() + " = v; }",
                        ctClass));
            }
        }
    }

    private static boolean hasMethod(CtClass cc, String name) {
        for (CtMethod m:cc.getDeclaredMethods())if(m.getName().equals(name)) return true;
        return false;
    }

    private CtMethod findMethod(CtClass ctClass, String name, String desc) throws NotFoundException {
        if (desc == null || desc.isEmpty()) {return ctClass.getDeclaredMethod(name);}
        for (CtMethod m : ctClass.getDeclaredMethods(name)) {
            if (m.getSignature().equals(desc) || m.getMethodInfo().getDescriptor().equals(desc)) {
                return m;
            }
        }
        return ctClass.getDeclaredMethod(name);
    }

    private CtClass resolveType(String typeName) throws NotFoundException {
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
            default -> classPool.get(typeName);
        };
    }

    private String buildMethodSource(AddMethod am, String body) {
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

    private static boolean matchesFull(MethodCall mc, String target) {
        String full = mc.getClassName() + "." + mc.getMethodName() + mc.getSignature();
        if (target.equals(full)) return true;
        String shortForm = mc.getMethodName() + mc.getSignature();
        return target.equals(shortForm);
    }

    private static String getBoxedName(CtClass primitive) {
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

    private static String getUnboxExpression(CtClass primitive) {
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

    private static boolean isCallbackMethod(Method m) {
        if (m.getReturnType() != void.class) return false;
        Class<?>[] params = m.getParameterTypes();
        if (params.length == 0) return false;
        return CallbackInfo.class.isAssignableFrom(params[params.length - 1]);
    }

    private static boolean isReturnableCallback(Method m) {
        Class<?>[] params = m.getParameterTypes();
        if (params.length == 0) return false;
        return CallbackInfoReturnable.class.isAssignableFrom(params[params.length - 1]);
    }

    private static boolean callbackAcceptsArgs(Method m) {
        Class<?>[] params = m.getParameterTypes();
        return params.length >= 3
                && params[0] == Object.class
                && params[1] == Object[].class
                && CallbackInfo.class.isAssignableFrom(params[params.length - 1]);
    }

    private static boolean isPrimitiveTypeName(String name) {
        if (name == null) return false;
        return switch (name) {
            case "boolean", "byte", "char", "short",
                 "int", "long", "float", "double", "void" -> true;
            default -> false;
        };
    }

    private static String getBoxedNameFromPrimitiveName(String name) {
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

    private static String getUnboxFromPrimitiveName(String name) {
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

    private static String stripOuterBraces(String src) {
        if (src == null) return "";
        String s = src.trim();
        if (s.startsWith("{") && s.endsWith("}")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private CtClass[] parseParamTypes(String params) throws NotFoundException {
        if (params == null || params.trim().isEmpty()) return new CtClass[0];
        String[] parts = params.split(",");
        CtClass[] result = new CtClass[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            int spaceIdx = p.lastIndexOf(' ');
            String typeName = spaceIdx > 0 ? p.substring(0, spaceIdx).trim() : p;
            result[i] = resolveType(typeName);
        }
        return result;
    }

    private void appendDispatchCore(StringBuilder sb, IClassPatch patch, Method m, String methodName, String selfExpr, String[] argRefs, boolean cancellable) {
        boolean returnable = isReturnableCallback(m);
        boolean acceptsArgs = callbackAcceptsArgs(m);
        int id = CallbackDispatcher.register(patch, m, returnable);
        String ciType = returnable ? CallbackInfoReturnable.class.getName() : CallbackInfo.class.getName();
        sb.append(ciType).append(" jffl_ci = new ").append(ciType)
          .append("(\"").append(methodName).append("\", ")
          .append(cancellable).append(");");
        if (acceptsArgs) {
            if (argRefs != null && argRefs.length > 0) {
                sb.append("Object[] jffl_args = new Object[")
                  .append(argRefs.length).append("];");
                for (int i = 0; i < argRefs.length; i++) {
                    sb.append("jffl_args[").append(i).append("] = ($w) ")
                      .append(argRefs[i]).append(";");
                }
            } else {
                sb.append("Object[] jffl_args = new Object[0];");
            }
            if (returnable) {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnableWithArgs(").append(id)
                  .append(", ").append(selfExpr).append(", jffl_args, (")
                  .append(CallbackInfoReturnable.class.getName()).append(") jffl_ci);");
            } else {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchWithArgs(").append(id)
                  .append(", ").append(selfExpr).append(", jffl_args, jffl_ci);");
            }
        } else {
            if (returnable) {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnable(").append(id)
                  .append(", ").append(selfExpr).append(", (")
                  .append(CallbackInfoReturnable.class.getName()).append(") jffl_ci);");
            } else {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatch(").append(id)
                  .append(", ").append(selfExpr).append(", jffl_ci);");
            }
        }
    }

    private static String[] argRefsForParams(int count) {
        String[] refs = new String[count];
        for (int i = 0; i < count; i++) refs[i] = "$" + (i + 1);
        return refs;
    }

    private static void appendValueWriteback(StringBuilder sb, String lhs, String typeName) {
        if (isPrimitiveTypeName(typeName)) {
            sb.append(lhs).append(" = ((")
              .append(getBoxedNameFromPrimitiveName(typeName))
              .append(") jffl_ci.getReturnValue()).")
              .append(getUnboxFromPrimitiveName(typeName)).append(";");
        } else {
            sb.append(lhs).append(" = (").append(typeName)
              .append(") jffl_ci.getReturnValue();");
        }
    }

    private String buildBodyReplacementCallback(IClassPatch patch, Method m, String methodName, CtMethod target, CtClass[] paramTypes, boolean isStatic) throws Exception {
        CtClass returnType = target.getReturnType();
        boolean returnable = isReturnableCallback(m);
        String selfExpr = isStatic ? "null" : "$0";
        String[] argRefs = argRefsForParams(paramTypes.length);

        StringBuilder sb = new StringBuilder("{");
        appendDispatchCore(sb, patch, m, methodName, selfExpr, argRefs, true);
        if (returnType == CtClass.voidType) {
        } else if (returnable) {
            appendCancelReturn(sb, returnType);
        } else {
            sb.append(getDefaultReturnStatement(returnType));
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildAddMethodCallback(IClassPatch patch, Method m, String methodName, CtClass returnType, CtClass[] paramTypes, boolean isStatic) throws Exception {
        boolean returnable = isReturnableCallback(m);
        String selfExpr = isStatic ? "null" : "$0";
        String[] argRefs = argRefsForParams(paramTypes.length);

        StringBuilder sb = new StringBuilder();
        appendDispatchCore(sb, patch, m, methodName, selfExpr, argRefs, true);
        if (returnType == CtClass.voidType) {
        } else if (returnable) {
            appendCancelReturn(sb, returnType);
        } else {
            sb.append(getDefaultReturnStatement(returnType));
        }
        return sb.toString();
    }

    private String buildModifyReturnValueCallback(IClassPatch patch, Method m, String methodName, CtMethod target) throws Exception {
        CtClass returnType = target.getReturnType();
        CtClass[] paramTypes = target.getParameterTypes();
        boolean returnable = isReturnableCallback(m);
        String[] argRefs = argRefsForParams(paramTypes.length);
        StringBuilder sb = new StringBuilder("{");
        boolean acceptsArgs = callbackAcceptsArgs(m);
        int id = CallbackDispatcher.register(patch, m, returnable);
        String ciType = returnable ? CallbackInfoReturnable.class.getName() : CallbackInfo.class.getName();
        sb.append(ciType).append(" jffl_ci = new ").append(ciType)
          .append("(\"").append(methodName).append("\", true);");
        if (returnable && returnType != CtClass.voidType) {
            sb.append("jffl_ci.setReturnValue(($w) $_);");
        }
        if (acceptsArgs) {
            sb.append("Object[] jffl_args = new Object[")
              .append(paramTypes.length).append("];");
            for (int i = 0; i < paramTypes.length; i++) {
                sb.append("jffl_args[").append(i).append("] = ($w) ")
                  .append(argRefs[i]).append(";");
            }
            if (returnable) {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnableWithArgs(").append(id)
                  .append(", $0, jffl_args, (")
                  .append(CallbackInfoReturnable.class.getName()).append(") jffl_ci);");
            } else {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchWithArgs(").append(id)
                  .append(", $0, jffl_args, jffl_ci);");
            }
        } else {
            if (returnable) {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnable(").append(id).append(", $0, (")
                  .append(CallbackInfoReturnable.class.getName()).append(") jffl_ci);");
            } else {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatch(").append(id).append(", $0, jffl_ci);");
            }
        }
        if (returnable && returnType != CtClass.voidType) {
            appendValueWriteback(sb, "$_", returnType.getName());
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildTryCatchCallback(IClassPatch patch, Method m, String methodName, CtMethod target, CtClass exType) throws Exception {
        CtClass returnType = target.getReturnType();
        boolean returnable = isReturnableCallback(m);
        boolean isStatic = (target.getModifiers() & Modifier.STATIC) != 0;
        String selfExpr = isStatic ? "null" : "$0";
        StringBuilder sb = new StringBuilder("{");
        appendDispatchCore(sb, patch, m, methodName, selfExpr, new String[]{"$1"}, true);
        sb.append("if (jffl_ci.isCancelled()) {");
        if (returnType == CtClass.voidType) {
            sb.append("return;");
        } else if (returnable) {
            appendCancelReturn(sb, returnType);
        } else {
            sb.append(getDefaultReturnStatement(returnType));
        }
        sb.append("} else { throw $1; }");
        sb.append("}");
        return sb.toString();
    }

    private String buildConstructorCallback(IClassPatch patch, Method m, String methodName, CtConstructor ctor) throws Exception {
        CtClass[] paramTypes = ctor.getParameterTypes();
        String[] argRefs = argRefsForParams(paramTypes.length);
        StringBuilder sb = new StringBuilder("{");
        appendDispatchCore(sb, patch, m, methodName, "$0", argRefs, false);
        sb.append("}");
        return sb.toString();
    }

    private String buildStaticInitCallback(IClassPatch patch, Method m, String methodName) {
        StringBuilder sb = new StringBuilder("{");
        appendDispatchCore(sb, patch, m, methodName, "null", new String[0], false);
        sb.append("}");
        return sb.toString();
    }

    private String buildExprValueCallback(IClassPatch patch, Method m, String methodName, String returnTypeName, String[] argRefs, String valueTypeName) {
        boolean returnable = isReturnableCallback(m);
        StringBuilder sb = new StringBuilder("{");
        sb.append("$_ = $proceed($$);");
        if (returnable) {
            boolean acceptsArgs = callbackAcceptsArgs(m);
            int id = CallbackDispatcher.register(patch, m, true);
            String ciType = CallbackInfoReturnable.class.getName();
            sb.append(ciType).append(" jffl_ci = new ").append(ciType)
              .append("(\"").append(methodName).append("\", true);");
            sb.append("jffl_ci.setReturnValue(($w) $_);");
            if (acceptsArgs) {
                if (argRefs != null && argRefs.length > 0) {
                    sb.append("Object[] jffl_args = new Object[")
                      .append(argRefs.length).append("];");
                    for (int i = 0; i < argRefs.length; i++) {
                        sb.append("jffl_args[").append(i)
                          .append("] = ($w) ").append(argRefs[i]).append(";");
                    }
                } else {
                    sb.append("Object[] jffl_args = new Object[0];");
                }
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnableWithArgs(").append(id)
                  .append(", $0, jffl_args, jffl_ci);");
            } else {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnable(").append(id).append(", $0, jffl_ci);");
            }
            appendValueWriteback(sb, "$_", valueTypeName);
        } else {
            appendDispatchCore(sb, patch, m, methodName, "$0", argRefs, false);
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildExprInsertionCallback(IClassPatch patch, Method m, String methodName, String[] argRefs) {
        StringBuilder sb = new StringBuilder("{");
        appendDispatchCore(sb, patch, m, methodName, "$0", argRefs, false);
        sb.append("}");
        return sb.toString();
    }

    private String buildFieldWriteCallback(IClassPatch patch, Method m, String methodName, String fieldType) {
        boolean returnable = isReturnableCallback(m);
        StringBuilder sb = new StringBuilder("{");
        if (returnable) {
            boolean acceptsArgs = callbackAcceptsArgs(m);
            int id = CallbackDispatcher.register(patch, m, true);
            String ciType = CallbackInfoReturnable.class.getName();
            sb.append(ciType).append(" jffl_ci = new ").append(ciType)
              .append("(\"").append(methodName).append("\", true);");
            sb.append("jffl_ci.setReturnValue(($w) $1);");
            if (acceptsArgs) {
                sb.append("Object[] jffl_args = new Object[]{ ($w) $1 };");
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnableWithArgs(").append(id)
                  .append(", $0, jffl_args, jffl_ci);");
            } else {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnable(").append(id).append(", $0, jffl_ci);");
            }
            appendValueWriteback(sb, "$1", fieldType);
        } else {
            appendDispatchCore(sb, patch, m, methodName, "$0", new String[]{"$1"}, false);
        }
        sb.append("$proceed($1);");
        sb.append("}");
        return sb.toString();
    }

    private String buildModifyArgCallback(IClassPatch patch, Method m, String methodName, String argRef, String argType) {
        boolean returnable = isReturnableCallback(m);
        StringBuilder sb = new StringBuilder("{");
        if (returnable) {
            boolean acceptsArgs = callbackAcceptsArgs(m);
            int id = CallbackDispatcher.register(patch, m, true);
            String ciType = CallbackInfoReturnable.class.getName();
            sb.append(ciType).append(" jffl_ci = new ").append(ciType)
              .append("(\"").append(methodName).append("\", true);");
            sb.append("jffl_ci.setReturnValue(($w) ").append(argRef).append(");");
            if (acceptsArgs) {
                sb.append("Object[] jffl_args = new Object[]{ ($w) ")
                  .append(argRef).append(" };");
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnableWithArgs(").append(id)
                  .append(", $0, jffl_args, jffl_ci);");
            } else {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnable(").append(id).append(", $0, jffl_ci);");
            }
            appendValueWriteback(sb, argRef, argType);
        } else {
            appendDispatchCore(sb, patch, m, methodName, "$0", new String[]{argRef}, false);
        }
        sb.append("$_ = $proceed($$);");
        sb.append("}");
        return sb.toString();
    }

    private String buildModifyVariableCallback(IClassPatch patch, Method m, String methodName, String varRef, String varType) {
        boolean returnable = isReturnableCallback(m);
        StringBuilder sb = new StringBuilder("{");
        if (returnable) {
            boolean acceptsArgs = callbackAcceptsArgs(m);
            int id = CallbackDispatcher.register(patch, m, true);
            String ciType = CallbackInfoReturnable.class.getName();
            sb.append(ciType).append(" jffl_ci = new ").append(ciType)
              .append("(\"").append(methodName).append("\", true);");
            sb.append("jffl_ci.setReturnValue(($w) ").append(varRef).append(");");
            if (acceptsArgs) {
                sb.append("Object[] jffl_args = new Object[]{ ($w) ")
                  .append(varRef).append(" };");
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnableWithArgs(").append(id)
                  .append(", $0, jffl_args, jffl_ci);");
            } else {
                sb.append(CallbackDispatcher.class.getName())
                  .append(".dispatchReturnable(").append(id).append(", $0, jffl_ci);");
            }
            appendValueWriteback(sb, varRef, varType);
        } else {
            appendDispatchCore(sb, patch, m, methodName, "$0", new String[]{varRef}, false);
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildModifyVariableLineCallback(IClassPatch patch, Method m, String methodName) {
        StringBuilder sb = new StringBuilder("{");
        appendDispatchCore(sb, patch, m, methodName, "$0", new String[0], false);
        sb.append("}");
        return sb.toString();
    }
}