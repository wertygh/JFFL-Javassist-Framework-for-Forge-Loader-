package net.wertygh.jffl.service;

import net.wertygh.jffl.api.ITransformerHook;
import net.wertygh.jffl.bridge.AsmBridge;
import net.wertygh.jffl.engine.PatchEngine;
import net.wertygh.jffl.registry.PatchRegistry;
import cpw.mods.modlauncher.api.*;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class JavassistTransformer implements ITransformer<ClassNode> {
    private static Logger LOGGER = LoggerFactory.getLogger(JavassistTransformer.class);
    public Set<Target> targets;
    public PatchEngine engine;
    public List<PatchRegistry.TransformerPluginEntry> plugins;

    public JavassistTransformer(Set<String> targetClasses, PatchEngine engine) {
        this(targetClasses, engine, List.of());
    }

    public JavassistTransformer(Set<String> targetClasses, PatchEngine engine, List<PatchRegistry.TransformerPluginEntry> plugins) {
        this.engine = engine;
        this.plugins = plugins == null ? List.of() : List.copyOf(plugins);
        Set<Target> t = new HashSet<>();
        for (String s : targetClasses) t.add(Target.targetClass(s));
        this.targets = Collections.unmodifiableSet(t);
    }

    @Override
    public @NotNull ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        String className = context.getClassName();
        byte[] original = AsmBridge.toBytes(input);
        byte[] transformed = engine.transform(className, original);
        TransformResult pluginResult = applyPlugins(className, original, transformed);
        transformed = pluginResult.bytes;
        if (transformed == original && !pluginResult.pluginRan) return input;
        ClassNode result = AsmBridge.toClassNode(transformed);
        AsmBridge.copyInto(result, input);
        return result;
    }

    private TransformResult applyPlugins(String className, byte[] original, byte[] current) {
        byte[] result = current == null ? original : current;
        boolean pluginRan = false;
        for (PatchRegistry.TransformerPluginEntry entry : plugins) {
            if (!shouldRunPlugin(entry, className)) continue;
            pluginRan = true;
            try {
                byte[] next = entry.plugin.toByte(new HookContext(className, original, result));
                if (next == null) {
                    LOGGER.warn("插件{}在{}上返回null, 返回之前的字节码", entry.displayName(), className);
                    continue;
                }
                result = next;
            } catch (Throwable t) {
                LOGGER.error("插件{}在{}上失败", entry.displayName(), className, t);
            }
        }
        return new TransformResult(result, pluginRan);
    }

    private static boolean shouldRunPlugin(PatchRegistry.TransformerPluginEntry entry, String className) {
        Set<String> targetClasses = entry.targetClasses;
        if (targetClasses.isEmpty()) return true;
        String dottedName = className == null ? "" : className.replace('/', '.');
        String internalName = className == null ? "" : className.replace('.', '/');
        return targetClasses.contains(className)
                || targetClasses.contains(dottedName)
                || targetClasses.contains(internalName);
    }

    private static class TransformResult {
        public byte[] bytes;
        public boolean pluginRan;

        TransformResult(byte[] bytes, boolean pluginRan) {
            this.bytes = bytes;
            this.pluginRan = pluginRan;
        }
    }

    private static class HookContext implements ITransformerHook {
        public String className;
        public byte[] originalBytes;
        public byte[] bytes;

        HookContext(String className, byte[] originalBytes, byte[] bytes) {
            this.className = className;
            this.originalBytes = originalBytes;
            this.bytes = bytes;
        }

        @Override public String getClassName() {return className;}
        @Override public String getInternalName() {return className == null ? "" : className.replace('.', '/');}
        @Override public byte[] getBytes() {return bytes;}
        @Override public byte[] getOriginalBytes() {return originalBytes;}
        @Override public boolean isPatched() {return originalBytes != bytes;}
    }

    @Override
    public @NotNull TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public @NotNull Set<Target> targets() {
        return targets;
    }

    public @NotNull TargetType getTargetType() {
        return TargetType.CLASS;
    }

    @Override
    public String[] labels() {
        return new String[]{"jffl-javassist"};
    }
}
