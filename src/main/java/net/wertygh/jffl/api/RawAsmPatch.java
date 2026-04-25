package net.wertygh.jffl.api;

import org.objectweb.asm.tree.ClassNode;

public interface RawAsmPatch {
    void transform(ClassNode classNode, PatchContext ctx) throws Exception;
}
