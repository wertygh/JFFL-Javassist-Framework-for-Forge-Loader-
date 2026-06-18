package net.wertygh.jffl.engine;

import net.wertygh.jffl.api.IClassPatch;
import net.wertygh.jffl.api.PatchContext;
import net.wertygh.jffl.api.RawAsmPatch;
import net.wertygh.jffl.api.annotation.DumpClass;
import net.wertygh.jffl.api.annotation.Patch;
import net.wertygh.jffl.api.annotation.PatchDependency;
import net.wertygh.jffl.bridge.AsmBridge;
import net.wertygh.jffl.env.DevEnvironment;
import net.wertygh.jffl.registry.PatchRegistry;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.LoaderClassPath;
import javassist.NotFoundException;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PatchEngine {
    private static Logger LOGGER = LoggerFactory.getLogger(PatchEngine.class);
    private static boolean DEV_DUMP = DevEnvironment.isDev()
            && "true".equalsIgnoreCase(System.getProperty("jffl.dev.dump"));
    public PatchRegistry registry;
    public ClassPool classPool;
    public PatchApplicator applicator;
    public AtomicBoolean mappingsLoadAttempted = new AtomicBoolean(false);
    public volatile boolean autoLoadMappings = true;

    private final Object materializeGate = new Object();
    private volatile boolean patchesMaterialized = false;
    private volatile boolean materializing = false;

    public PatchEngine(PatchRegistry registry) {
        this.registry = registry;
        this.classPool = new ClassPool(true);
        ClassLoader cl = PatchEngine.class.getClassLoader();
        this.classPool.appendClassPath(new LoaderClassPath(cl));
        this.applicator = new PatchApplicator(classPool);
    }
    
    public PatchEngine(PatchRegistry registry, ClassLoader parentLoader) {
        this.registry = registry;
        this.classPool = new ClassPool(true);
        if (parentLoader != null) {
            this.classPool.appendClassPath(new LoaderClassPath(parentLoader));
        }
        this.applicator = new PatchApplicator(classPool);
    }
    
    public void appendClassPath(String path) {
        try {
            classPool.appendClassPath(path);
        } catch (NotFoundException e) {
            LOGGER.warn("无法添加classpath: {}", path, e);
        }
    }

    public ClassPool getClassPool() {return classPool;}

    public void loadMappings(Path path) {MappingLoader.loadFromFile(path);}
    public void loadMappings(File file) {MappingLoader.loadFromFile(file);}
    public void loadMappings(InputStream stream) {MappingLoader.loadFromStream(stream);}
    public void loadMappingsFromResource(String p) {MappingLoader.loadFromResource(p);}
    public void autoDiscoverMappings() {MappingLoader.autoDiscover();}
    public void setAutoLoadMappings(boolean v) {this.autoLoadMappings = v;}

    public byte[] transform(String className, byte[] classBytes) {
        ensurePatchesMaterialized();
        if (autoLoadMappings
                && DevEnvironment.isProduction()
                && mappingsLoadAttempted.compareAndSet(false, true)
                && !isJavassistInternal(className)) {
            try {
                MappingLoader.autoDiscover();
            } catch (Throwable t) {
                LOGGER.warn("SRG映射失败", t);
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
                LOGGER.warn("类'{}'被JFFL修补, 也是Mixin目标, 侵入性过大的注解可能会出问题.", className);
            }
        }

        boolean shouldDump = DEV_DUMP;
        String dumpDir = ".jffl-dump";
        try {
            CtClass ctClass = classPool.makeClass(
                new ByteArrayInputStream(classBytes), false);
            try {
                PatchContext ctx = new PatchContext(className, classPool);
                List<PatchRegistry.PatchEntry> active = new ArrayList<>(patches.size());
                for (PatchRegistry.PatchEntry e : patches) {
                    if (e.skeleton || e.patch == null) {
                        LOGGER.warn("未激活的骨架条目: {} -> {}",
                                e.patchClassName, className);
                        continue;
                    }
                    if (ConditionEvaluator.shouldApply(
                            e.patch.getClass(), classPool, ctClass)) {
                        active.add(e);
                    } else if (DevEnvironment.isDev()) {
                        LOGGER.debug("[DEV] @ConditionalPatch 不满足 {} -> {}",
                                e.patch.getClass().getSimpleName(), className);
                    }
                }
                for (PatchRegistry.PatchEntry entry : active) {
                    try {
                        if (DevEnvironment.isDev()) {
                            LOGGER.debug("[DEV] 应用补丁: {} → {} (优先级 {}, 来源={})",
                                    entry.patchClassName, className,
                                    entry.priority, entry.sourceId);
                        }
                        applicator.applyPatch(ctClass, entry, ctx);
                        DumpClass dc = entry.patch.getClass()
                            .getAnnotation(DumpClass.class);
                        if (dc != null) {
                            shouldDump = true;
                            dumpDir = dc.dir();
                        }
                    } catch (Exception e) {
                        LOGGER.error("补丁{}在类{}上失败",
                                entry.displayName(), className, e);
                        if (DevEnvironment.isDev()) {
                            LOGGER.error("[DEV] 堆栈:", e);
                        }
                    } finally {
                        flushWarnings(className, ctx);
                    }
                }
                byte[] result = ctClass.toBytecode();
                boolean anyAsm = active.stream()
                    .anyMatch(e -> e.patch instanceof RawAsmPatch);
                if (anyAsm) {
                    ClassNode node = AsmBridge.toClassNode(result);
                    PatchContext ctxAsm = new PatchContext(className, classPool);
                    for (PatchRegistry.PatchEntry entry : active) {
                        if (entry.patch instanceof RawAsmPatch asm) {
                            try {
                                asm.transform(node, ctxAsm);
                            } catch (Exception e) {
                                LOGGER.error("RawAsmPatch {}在{}上失败",
                                        entry.displayName(), className, e);
                            } finally {
                                flushWarnings(className, ctxAsm);
                            }
                        }
                    }
                    result = AsmBridge.toBytes(node);
                }
                if (shouldDump) dumpClassBytes(className, result, dumpDir);
                return result;
            } finally {
                ctClass.detach();
            }
        } catch (Exception e) {
            LOGGER.error("类{}的PatchEngine失败", className, e);
            return classBytes;
        }
    }
    
    private void ensurePatchesMaterialized() {
        if (patchesMaterialized) return;
        synchronized (materializeGate) {
            if (patchesMaterialized) return;
            List<PatchRegistry.DeferredPatchEntry> deferred =
                registry.getDeferredPatches();
            if (deferred.isEmpty()) {
                patchesMaterialized = true;
                return;
            }
            materializing = true;
            try {
                materializeDeferredPatches();
            } finally {
                materializing = false;
            }
        }
    }
    
    private void materializeDeferredPatches() {
        List<PatchRegistry.DeferredPatchEntry> deferred =
            new ArrayList<>(registry.getDeferredPatches());
        if (deferred.isEmpty()) {
            patchesMaterialized = true;
            return;
        }
        ClassLoader gameCL = Thread.currentThread().getContextClassLoader();
        Map<String, List<PatchRegistry.DeferredPatchEntry>> grouped = new LinkedHashMap<>();
        for (PatchRegistry.DeferredPatchEntry dp : deferred) {
            grouped.computeIfAbsent(dp.sourceJarPath, k -> new ArrayList<>()).add(dp);
        }
        Map<String, URLClassLoader> jarLoaders = new LinkedHashMap<>();
        Map<String, Class<?>> loadedClasses = new LinkedHashMap<>();
        for (var groupEntry : grouped.entrySet()) {
            String jarPath = groupEntry.getKey();
            List<PatchRegistry.DeferredPatchEntry> entries = groupEntry.getValue();
            URLClassLoader loader;
            try {
                URL url = new File(jarPath).toURI().toURL();
                loader = new URLClassLoader(new URL[]{url}, gameCL);
                jarLoaders.put(jarPath, loader);
                registry.patchClassLoaders.add(loader);
            } catch (Exception e) {
                LOGGER.error("无法为JAR创建ClassLoader: {}", jarPath, e);
                continue;
            }
            for (PatchRegistry.DeferredPatchEntry dp : entries) {
                try {
                    Class<?> cls = Class.forName(dp.patchClassName, false, loader);
                    loadedClasses.put(dp.patchClassName, cls);
                    Patch[] anns = cls.getAnnotationsByType(Patch.class);
                    if (anns.length == 0) {
                        LOGGER.warn("补丁类{}缺少@Patch注解, 跳过", dp.patchClassName);
                        continue;
                    }
                    for (Patch ann : anns) {
                        List<String> deps = collectRequiredDependencies(cls, ann);
                        List<String> optDeps = collectOptionalDependencies(cls);
                        registry.registerSkeleton(
                            ann.value(), ann.priority(),
                            dp.patchClassName, dp.sourceJarPath,
                            deps, optDeps
                        );
                    }
                    LOGGER.debug("骨架注册: {}", dp.patchClassName);
                } catch (ClassNotFoundException e) {
                    LOGGER.error("未找到补丁类: {} (来自{})",
                            dp.patchClassName, jarPath, e);
                } catch (Exception e) {
                    LOGGER.error("读取补丁类注解失败: {} (来自{})",
                            dp.patchClassName, jarPath, e);
                }
            }
        }
        patchesMaterialized = true;
        for (var entry : loadedClasses.entrySet()) {
            String className = entry.getKey();
            Class<?> cls = entry.getValue();
            try {
                IClassPatch patch = (IClassPatch) cls.getDeclaredConstructor().newInstance();
                registry.activateSkeleton(className, patch);
            } catch (Exception e) {
                LOGGER.error("实例化补丁类失败: {}", className, e);
            }
        }
        registry.clearDeferredPatches();
        LOGGER.info("补丁实例化完成: {}个加载, {}个激活",
                loadedClasses.size(),
                registry.getSkeletonEntries().isEmpty() ? loadedClasses.size() :
                loadedClasses.size() - registry.getSkeletonEntries().size());
    }

    private static List<String> collectRequiredDependencies(Class<?> clazz, Patch patch) {
        LinkedHashSet<String> deps = new LinkedHashSet<>();
        for (String dep : patch.dependsOn()) {
            if (dep != null && !dep.isBlank()) deps.add(dep.trim());
        }
        for (PatchDependency dep : clazz.getAnnotationsByType(PatchDependency.class)) {
            if (!dep.optional() && dep.value() != null && !dep.value().isBlank())
                deps.add(dep.value().trim());
        }
        return List.copyOf(deps);
    }

    private static List<String> collectOptionalDependencies(Class<?> clazz) {
        LinkedHashSet<String> deps = new LinkedHashSet<>();
        for (PatchDependency dep : clazz.getAnnotationsByType(PatchDependency.class)) {
            if (dep.optional() && dep.value() != null && !dep.value().isBlank())
                deps.add(dep.value().trim());
        }
        return List.copyOf(deps);
    }
    
    private void flushWarnings(String className, PatchContext ctx) {
        for (String warning : ctx.drainWarnings()) {
            LOGGER.warn("[{}] {}", className, warning);
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

    private static boolean isJavassistInternal(String className) {
        if (className == null) return false;
        String n = className.replace('/', '.');
        return n.startsWith("javassist.");
    }
}