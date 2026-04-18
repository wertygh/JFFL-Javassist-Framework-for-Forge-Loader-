package net.wertygh.jffl.service;

import net.wertygh.jffl.bridge.AsmBridge;
import net.wertygh.jffl.engine.PatchEngine;
import cpw.mods.modlauncher.api.*;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.ClassNode;

import java.util.Set;
import java.util.stream.Collectors;

public class JavassistTransformer implements ITransformer<ClassNode> {
    private final Set<Target> targets;
    private final PatchEngine engine;

    public JavassistTransformer(Set<String> targetClasses, PatchEngine engine) {
        this.engine = engine;
        this.targets = targetClasses.stream()
                .map(Target::targetClass)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public @NotNull ClassNode transform(ClassNode input, ITransformerVotingContext context) {
        String className = context.getClassName();
        byte[] original = AsmBridge.toBytes(input);
        byte[] transformed = engine.transform(className, original);
        if (transformed == original) return input;
        ClassNode result = AsmBridge.toClassNode(transformed);
        AsmBridge.copyInto(result, input);
        return input;
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