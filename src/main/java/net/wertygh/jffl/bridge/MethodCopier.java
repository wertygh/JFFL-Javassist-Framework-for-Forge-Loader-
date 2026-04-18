package net.wertygh.jffl.bridge;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class MethodCopier {
    private static final Logger LOGGER = LoggerFactory.getLogger(MethodCopier.class);
    
    public static String copyMethod(byte[] patchClassBytes, String sourceMethodName, String sourceMethodDesc, ClassNode targetNode, String mergedName) {
        ClassNode patchNode = new ClassNode();
        new ClassReader(patchClassBytes).accept(patchNode, ClassReader.EXPAND_FRAMES);
        MethodNode source = findMethod(patchNode, sourceMethodName, sourceMethodDesc);
        if (source == null) {
            throw new IllegalArgumentException("方法 " + sourceMethodName + 
                    (sourceMethodDesc != null ? sourceMethodDesc : "") 
                    + " 未在补丁类 " + patchNode.name + " 中找到");
        }
        String finalName = mergedName != null ? mergedName : "jffl$merged$" + sourceMethodName;
        String patchInternalName = patchNode.name;
        String targetInternalName = targetNode.name;
        MethodNode copied = cloneMethod(source);
        copied.name = finalName;
        copied.access = (copied.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        copied.access &= ~Opcodes.ACC_STATIC;
        remapOwnerReferences(copied, patchInternalName, targetInternalName);
        for (MethodNode existing : targetNode.methods) {
            if (existing.name.equals(finalName) && existing.desc.equals(copied.desc)) {
                LOGGER.warn("方法{}{}已经存在于{}",
                        finalName, copied.desc, targetInternalName);
                return finalName;
            }
        }
        copied.access |= Opcodes.ACC_SYNTHETIC;
        targetNode.methods.add(copied);
        LOGGER.debug("从{}复制了方法{}{}到{}作为{}",
                sourceMethodName, source.desc, patchInternalName, targetInternalName, finalName);
        return finalName;
    }
    
    public static CopyResult copyMethod(byte[] patchClassBytes, String sourceMethodName, String sourceMethodDesc, byte[] targetClassBytes, String mergedName) {
        ClassNode targetNode = new ClassNode();
        new ClassReader(targetClassBytes).accept(targetNode, ClassReader.EXPAND_FRAMES);
        String finalName = copyMethod(patchClassBytes, sourceMethodName, sourceMethodDesc, targetNode, mergedName);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        targetNode.accept(cw);
        return new CopyResult(cw.toByteArray(), finalName);
    }

    public record CopyResult(byte[] classBytes, String mergedMethodName) {}
    
    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(name) && (desc == null || mn.desc.equals(desc))) {
                return mn;
            }
        }
        return null;
    }
    
    private static MethodNode cloneMethod(MethodNode source) {
        MethodNode copy = new MethodNode(
                source.access, source.name, source.desc,
                source.signature,
                source.exceptions != null?source.exceptions.toArray(new String[0]):null
        );
        Map<LabelNode, LabelNode> labelMap = new HashMap<>();
        for (AbstractInsnNode insn = source.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode ln) labelMap.put(ln, new LabelNode());
        }
        for (AbstractInsnNode insn = source.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            copy.instructions.add(insn.clone(labelMap));
        }
        if (source.tryCatchBlocks != null) {
            copy.tryCatchBlocks = new ArrayList<>();
            for (TryCatchBlockNode tcb : source.tryCatchBlocks) {
                copy.tryCatchBlocks.add(new TryCatchBlockNode(
                        labelMap.getOrDefault(tcb.start, tcb.start),
                        labelMap.getOrDefault(tcb.end, tcb.end),
                        labelMap.getOrDefault(tcb.handler, tcb.handler),
                        tcb.type
                ));
            }
        }
        if (source.localVariables != null) {
            copy.localVariables = new ArrayList<>();
            for (LocalVariableNode lv : source.localVariables) {
                copy.localVariables.add(new LocalVariableNode(
                        lv.name, lv.desc, lv.signature,
                        labelMap.getOrDefault(lv.start, lv.start),
                        labelMap.getOrDefault(lv.end, lv.end),
                        lv.index
                ));
            }
        }
        if (source.visibleAnnotations != null) {
            copy.visibleAnnotations = new ArrayList<>(source.visibleAnnotations);
        }
        if (source.invisibleAnnotations != null) {
            copy.invisibleAnnotations = new ArrayList<>(source.invisibleAnnotations);
        }
        copy.maxStack = source.maxStack;
        copy.maxLocals = source.maxLocals;
        copy.visibleParameterAnnotations = source.visibleParameterAnnotations;
        copy.invisibleParameterAnnotations = source.invisibleParameterAnnotations;
        return copy;
    }
    
    private static void remapOwnerReferences(MethodNode method, String patchOwner, String targetOwner) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode fin) {
                if (fin.owner.equals(patchOwner)) {
                    fin.owner = targetOwner;
                }
            } else if (insn instanceof MethodInsnNode min) {
                if (min.owner.equals(patchOwner)) {
                    min.owner = targetOwner;
                }
            } else if (insn instanceof TypeInsnNode tin) {
                if (tin.desc.equals(patchOwner)) {
                    tin.desc = targetOwner;
                } else if (tin.desc.contains(patchOwner)) {
                    tin.desc = tin.desc.replace(patchOwner, targetOwner);
                }
            } else if (insn instanceof LdcInsnNode ldc) {
                if (ldc.cst instanceof Type t) {
                    if (t.getSort() == Type.OBJECT && t.getInternalName().equals(patchOwner)) {
                        ldc.cst = Type.getObjectType(targetOwner);
                    }
                }
            } else if (insn instanceof FrameNode frame) {
                remapFrameTypes(frame.local, patchOwner, targetOwner);
                remapFrameTypes(frame.stack, patchOwner, targetOwner);
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                for (int i = 0; i < indy.bsmArgs.length; i++) {
                    if (indy.bsmArgs[i] instanceof Type t) {
                        String desc = t.getDescriptor().replace(
                                "L" + patchOwner + ";",
                                "L" + targetOwner + ";"
                        );
                        if (!desc.equals(t.getDescriptor())) {
                            indy.bsmArgs[i] = Type.getType(desc);
                        }
                    } else if (indy.bsmArgs[i] instanceof Handle h) {
                        if (h.getOwner().equals(patchOwner)) {
                            indy.bsmArgs[i] = new Handle(
                                    h.getTag(), targetOwner, h.getName(), h.getDesc(), h.isInterface()
                            );
                        }
                    }
                }
            }
        }
        method.desc = method.desc.replace("L"+patchOwner+";", "L"+targetOwner+";");
        if (method.signature != null) {
            method.signature = method.signature.replace("L" + patchOwner + ";", "L" + targetOwner + ";");
        }
        if (method.localVariables != null) {
            for (LocalVariableNode lv : method.localVariables) {
                lv.desc = lv.desc.replace("L"+patchOwner+";", "L"+targetOwner+";");
                if (lv.signature != null) {
                    lv.signature = lv.signature.replace("L" + patchOwner + ";", "L" + targetOwner + ";");
                }
            }
        }
    }

    private static void remapFrameTypes(List<Object> types, String patchOwner, String targetOwner) {
        if (types == null) return;
        for (int i = 0; i < types.size(); i++) {
            Object t = types.get(i);
            if (patchOwner.equals(t)) {
                types.set(i, targetOwner);
            }
        }
    }
}