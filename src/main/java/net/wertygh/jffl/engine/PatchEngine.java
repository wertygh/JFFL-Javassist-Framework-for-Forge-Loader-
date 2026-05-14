package net.wertygh.jffl.engine;

import net.wertygh.jffl.api.PatchContext;
import net.wertygh.jffl.api.RawAsmPatch;
import net.wertygh.jffl.api.annotation.DumpClass;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
                List<PatchRegistry.PatchEntry> active = new ArrayList<>(patches.size());
                for (PatchRegistry.PatchEntry e : patches) {
                    if (ConditionEvaluator.shouldApply(e.patch.getClass(), classPool, ctClass)) {
                        active.add(e);
                    } else if (DevEnvironment.isDev()) {
                        LOGGER.debug("[DEV] @ConditionalPatch 不满足, 跳过 {} -> {}",
                                e.patch.getClass().getSimpleName(), className);
                    }
                }
                for (PatchRegistry.PatchEntry entry : active) {
                    try {
                        if (DevEnvironment.isDev()) {
                            LOGGER.debug("[DEV] 应用补丁: {} → {} (优先级 {}, 来源={})",
                                    entry.patchClassName, className, entry.priority, entry.sourceId);
                        }
                        applicator.applyPatch(ctClass, entry, ctx);
                        DumpClass dc = entry.patch.getClass().getAnnotation(DumpClass.class);
                        if (dc != null) {
                            shouldDump = true;
                            dumpDir = dc.dir();
                        }
                    } catch (Exception e) {
                        LOGGER.error("补丁{}在类{}上失败", entry.displayName(), className, e);
                        if (DevEnvironment.isDev()) {
                            LOGGER.error("[DEV] 补丁失败的详细堆栈:", e);
                        }
                    } finally {
                        flushWarnings(className, ctx);
                    }
                }
                byte[] result = ctClass.toBytecode();
                boolean anyAsm = active.stream().anyMatch(e -> e.patch instanceof RawAsmPatch);
                if (anyAsm) {
                    ClassNode node = AsmBridge.toClassNode(result);
                    PatchContext ctxAsm = new PatchContext(className, classPool);
                    for (PatchRegistry.PatchEntry entry : active) {
                        if (entry.patch instanceof RawAsmPatch asm) {
                            try {
                                asm.transform(node, ctxAsm);
                            } catch (Exception e) {
                                LOGGER.error("RawAsmPatch {}在{}上失败", entry.displayName(), className, e);
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
