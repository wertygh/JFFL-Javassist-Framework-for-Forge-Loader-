package net.wertygh.jffl.bridge;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

public class AsmBridge {
    public static byte[] toBytes(ClassNode node) {
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    public static ClassNode toClassNode(byte[] bytes) {
        ClassNode node = new ClassNode();
        ClassReader cr = new ClassReader(bytes);
        cr.accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    public static void copyInto(ClassNode source, ClassNode target) {
        target.version = source.version;
        target.access = source.access;
        target.name = source.name;
        target.signature = source.signature;
        target.superName = source.superName;
        target.interfaces = source.interfaces;
        target.sourceFile = source.sourceFile;
        target.sourceDebug = source.sourceDebug;
        target.module = source.module;
        target.outerClass = source.outerClass;
        target.outerMethod = source.outerMethod;
        target.outerMethodDesc = source.outerMethodDesc;
        target.visibleAnnotations = source.visibleAnnotations;
        target.invisibleAnnotations = source.invisibleAnnotations;
        target.visibleTypeAnnotations = source.visibleTypeAnnotations;
        target.invisibleTypeAnnotations = source.invisibleTypeAnnotations;
        target.attrs = source.attrs;
        target.innerClasses = source.innerClasses;
        target.nestHostClass = source.nestHostClass;
        target.nestMembers = source.nestMembers;
        target.permittedSubclasses = source.permittedSubclasses;
        target.recordComponents = source.recordComponents;
        target.fields.clear();
        target.fields.addAll(source.fields);
        target.methods.clear();
        target.methods.addAll(source.methods);
    }
}
