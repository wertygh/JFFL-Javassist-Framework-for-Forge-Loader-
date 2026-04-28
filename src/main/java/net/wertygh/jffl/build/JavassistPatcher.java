package net.wertygh.jffl.build;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

// 编译期功能, 运行时无用
public class JavassistPatcher {
    private static final String CHM_IMPL = "java/util/concurrent/ConcurrentHashMap";
    private static final String MCG_INTERNAL = "javassist/compiler/MemberCodeGen";
    private static final String TC_INTERNAL = "javassist/compiler/TypeChecker";
    private static final String JAVAC_INTERNAL = "javassist/compiler/Javac";
    private static final String CTCLASS_TYPE_INTERNAL = "javassist/CtClassType";
    private static final String MEMBER_AST = "javassist/compiler/ast/Member";
    private static final String SYMBOL_AST = "javassist/compiler/ast/Symbol";
    private static final String ASTLIST = "javassist/compiler/ast/ASTList";
    private static final String SB_INTERNAL = "java/lang/StringBuilder";
    private static final String STRING_DESC = "Ljava/lang/String;";
    private static final String SB_DESC = "Ljava/lang/StringBuilder;";
    private static final String CTCLASS = "javassist/CtClass";
    private static final String MAP_DESC = "Ljava/util/Map;";
    private static final String MAP_IFACE = "java/util/Map";
    private static final String STRING = "java/lang/String";
    private static final String REMAP_OVL_DESC = "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;";
    private static final String REMAP_DESC = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
    private static final String ADD_MAP_DESC = "(Ljava/lang/String;Ljava/lang/String;)V";

    public static byte[] patchJavassistClass(String className, byte[] classBytes) {
        ClassNode cn = toClassNode(classBytes);
        switch (cn.name) {
            case MCG_INTERNAL:
                patchMemberCodeGen(cn);
                break;
            case TC_INTERNAL:
                patchTypeChecker(cn);
                break;
            case JAVAC_INTERNAL:
                patchJavac(cn);
                break;
            case CTCLASS_TYPE_INTERNAL:
                patchCtClassType(cn);
                break;
            default:
                return classBytes;
        }
        return toBytes(cn);
    }

    private static ClassNode toClassNode(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        return cn;
    }

    private static byte[] toBytes(ClassNode cn) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static void patchMemberCodeGen(ClassNode cn) {
        System.out.println("[JavassistPatcher] 修补 MemberCodeGen");
        cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$fieldMap", MAP_DESC, null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$methodMap", MAP_DESC, null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$methodDescMap", MAP_DESC, null, null));

        MethodNode clinit = findMethod(cn, "<clinit>", "()V");
        InsnList init = buildMapInitInsns();
        if (clinit != null) {
            clinit.instructions.insertBefore(clinit.instructions.getFirst(), init);
            clinit.maxStack = Math.max(clinit.maxStack, 2);
        } else {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(init);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            clinit.maxStack = 2;
            clinit.maxLocals = 0;
            cn.methods.add(clinit);
        }

        cn.methods.add(buildRemapFieldMethod());
        cn.methods.add(buildRemapMethodMethod());
        cn.methods.add(buildRemapMethodOverload());
        cn.methods.add(buildAddFieldMappingMethod());
        cn.methods.add(buildAddMethodMappingMethod());
        cn.methods.add(buildAddMethodDescMappingMethod());
        cn.methods.add(buildClearMappingsMethod());
        cn.methods.add(buildHasMappingsMethod());

        MethodNode fieldAccess = findMethod(cn, "fieldAccess", null);
        if (fieldAccess != null) {
            patchFieldAccessForRemap(fieldAccess, MCG_INTERNAL);
        } else {
            System.out.println("[JavassistPatcher] 警告：未找到 MemberCodeGen.fieldAccess");
        }

        MethodNode atCallExpr = findMethod(cn, "atCallExpr",
                "(Ljavassist/compiler/ast/CallExpr;)V");
        if (atCallExpr != null) {
            String callCoreDesc = "(L" + CTCLASS + ";" + STRING_DESC +
                    "Ljavassist/compiler/ast/ASTList;" +
                    "ZZI" +
                    "Ljavassist/compiler/MemberResolver$Method;)V";
            int argsSlot = findArgsSlot(atCallExpr);
            patchAtCallExprForMethodRemap(atCallExpr, MCG_INTERNAL,
                    "atMethodCallCore", callCoreDesc,
                    2, 3, argsSlot);
        } else {
            System.out.println("[JavassistPatcher] 警告：未找到 MemberCodeGen.atCallExpr");
        }
    }

    private static void patchTypeChecker(ClassNode cn) {
        System.out.println("[JavassistPatcher] 修补 TypeChecker");
        MethodNode fieldAccess = findMethod(cn, "fieldAccess", null);
        if (fieldAccess != null) {
            patchFieldAccessForRemap(fieldAccess, TC_INTERNAL);
        }
        MethodNode atCallExpr = findMethod(cn, "atCallExpr",
                "(Ljavassist/compiler/ast/CallExpr;)V");
        if (atCallExpr != null) {
            int argsSlot = findArgsSlot(atCallExpr);
            patchTcAtCallExprForMethodRemap(atCallExpr, 2, 3, argsSlot);
        }
    }

    private static void patchJavac(ClassNode cn) {
        System.out.println("[JavassistPatcher] 修补 Javac");
        cn.methods.add(buildJavacBridge("addFieldMapping", "jffl$addFieldMapping"));
        cn.methods.add(buildJavacBridge("addMethodMapping", "jffl$addMethodMapping"));
        cn.methods.add(buildJavacBridge("addMethodDescMapping", "jffl$addMethodDescMapping"));
        cn.methods.add(buildJavacAddMethodOverloadMapping());
        cn.methods.add(buildJavacLoadMappings());
        cn.methods.add(buildJavacLoadMappingsFromFile());
        cn.methods.add(buildJavacAutoDiscoverMappings());
    }

    private static void patchCtClassType(ClassNode cn) {
        System.out.println("[JavassistPatcher] 修补 CtClassType");
        MethodNode gdf2 = findMethod(cn, "getDeclaredField2",
                "(" + STRING_DESC + STRING_DESC + ")Ljavassist/CtField;");
        if (gdf2 != null) {
            gdf2.instructions.insertBefore(gdf2.instructions.getFirst(), buildFieldRemapPatch(0, 1));
            gdf2.maxStack = Math.max(gdf2.maxStack, 3);
        }
        MethodNode gm0 = findMethod(cn, "getMethod0",
                "(Ljavassist/CtClass;" + STRING_DESC + STRING_DESC + ")Ljavassist/CtMethod;");
        if (gm0 != null) {
            gm0.instructions.insertBefore(gm0.instructions.getFirst(), buildMethodRemapPatch(0, 1));
            gm0.maxStack = Math.max(gm0.maxStack, 3);
        }
        MethodNode gdm1 = findMethod(cn, "getDeclaredMethod",
                "(" + STRING_DESC + ")Ljavassist/CtMethod;");
        if (gdm1 != null) {
            gdm1.instructions.insertBefore(gdm1.instructions.getFirst(), buildMethodRemapPatch(0, 1));
            gdm1.maxStack = Math.max(gdm1.maxStack, 3);
        }
        MethodNode gdm2 = findMethod(cn, "getDeclaredMethod",
                "(" + STRING_DESC + "[Ljavassist/CtClass;)Ljavassist/CtMethod;");
        if (gdm2 != null) {
            gdm2.instructions.insertBefore(gdm2.instructions.getFirst(), buildMethodRemapPatch(0, 1));
            gdm2.maxStack = Math.max(gdm2.maxStack, 3);
        }
        MethodNode gdms = findMethod(cn, "getDeclaredMethods",
                "(" + STRING_DESC + ")[Ljavassist/CtMethod;");
        if (gdms != null) {
            gdms.instructions.insertBefore(gdms.instructions.getFirst(), buildMethodRemapPatch(0, 1));
            gdms.maxStack = Math.max(gdms.maxStack, 3);
        }
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && (desc == null || m.desc.equals(desc)))
                return m;
        }
        return null;
    }

    private static MethodInsnNode findMethodCall(MethodNode mn, int opcode, String owner, String name, String desc) {
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn.getOpcode() == opcode && insn instanceof MethodInsnNode min
                    && min.owner.equals(owner) && min.name.equals(name) && min.desc.equals(desc))
                return min;
        }
        return null;
    }

    private static AbstractInsnNode findPrecedingAload0(AbstractInsnNode start) {
        AbstractInsnNode cur = start.getPrevious();
        while (cur != null) {
            if (cur.getType() == AbstractInsnNode.JUMP_INSN) break;
            if (cur.getOpcode() == Opcodes.ALOAD && cur instanceof VarInsnNode v && v.var == 0)
                return cur;
            cur = cur.getPrevious();
        }
        return null;
    }

    private static VarInsnNode findNextAstore(AbstractInsnNode start) {
        AbstractInsnNode cur = start.getNext();
        while (cur != null) {
            if (cur.getOpcode() == Opcodes.ASTORE && cur instanceof VarInsnNode v)
                return v;
            cur = cur.getNext();
        }
        return null;
    }

    private static int findArgsSlot(MethodNode mn) {
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn.getOpcode() == Opcodes.CHECKCAST && insn instanceof TypeInsnNode tin
                    && ASTLIST.equals(tin.desc)) {
                AbstractInsnNode next = insn.getNext();
                if (next != null && next.getOpcode() == Opcodes.ASTORE && next instanceof VarInsnNode v)
                    return v.var;
            }
        }
        return 5;
    }

    private static void emitStringConcat(InsnList il, ConcatPart... parts) {
        il.add(new TypeInsnNode(Opcodes.NEW, SB_INTERNAL));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, SB_INTERNAL, "<init>", "()V"));
        for (ConcatPart p : parts) {
            if (p instanceof StringPart sp) {
                il.add(new LdcInsnNode(sp.value));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SB_INTERNAL, "append",
                        "(" + STRING_DESC + ")" + SB_DESC));
            } else if (p instanceof AloadPart ap) {
                il.add(new VarInsnNode(Opcodes.ALOAD, ap.slot));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SB_INTERNAL, "append",
                        "(" + STRING_DESC + ")" + SB_DESC));
            } else if (p instanceof IloadPart ip) {
                il.add(new VarInsnNode(Opcodes.ILOAD, ip.slot));
                il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SB_INTERNAL, "append",
                        "(I)" + SB_DESC));
            }
        }
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SB_INTERNAL, "toString", "()" + STRING_DESC));
    }

    private sealed interface ConcatPart {}
    private record StringPart(String value) implements ConcatPart {}
    private record AloadPart(int slot) implements ConcatPart {}
    private record IloadPart(int slot) implements ConcatPart {}

    private static InsnList buildMapInitInsns() {
        InsnList il = new InsnList();
        String[] maps = {"jffl$fieldMap", "jffl$methodMap", "jffl$methodDescMap"};
        for (String map : maps) {
            il.add(new TypeInsnNode(Opcodes.NEW, CHM_IMPL));
            il.add(new InsnNode(Opcodes.DUP));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CHM_IMPL, "<init>", "()V"));
            il.add(new FieldInsnNode(Opcodes.PUTSTATIC, MCG_INTERNAL, map, MAP_DESC));
        }
        return il;
    }

    private static MethodNode buildRemapFieldMethod() {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$remapField", REMAP_DESC, null, null);
        InsnList il = mn.instructions;
        LabelNode returnOriginal = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        emitStringConcat(il, new AloadPart(0), new StringPart("."), new AloadPart(1));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$fieldMap", MAP_DESC));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, STRING));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(returnOriginal);
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 3;
        mn.maxLocals = 4;
        return mn;
    }

    private static MethodNode buildRemapMethodMethod() {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$remapMethod", REMAP_DESC, null, null);
        InsnList il = mn.instructions;
        LabelNode returnOriginal = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        emitStringConcat(il, new AloadPart(0), new StringPart("."), new AloadPart(1));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$methodMap", MAP_DESC));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, STRING));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(returnOriginal);
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 3;
        mn.maxLocals = 4;
        return mn;
    }

    private static MethodNode buildRemapMethodOverload() {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$remapMethodOverload", REMAP_OVL_DESC, null, null);
        InsnList il = mn.instructions;
        LabelNode trySimple = new LabelNode();
        LabelNode returnOriginal = new LabelNode();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        emitStringConcat(il, new AloadPart(0), new StringPart("."), new AloadPart(1),
                new StringPart("#"), new IloadPart(2));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$methodDescMap", MAP_DESC));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        il.add(new VarInsnNode(Opcodes.ASTORE, 4));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new JumpInsnNode(Opcodes.IFNULL, trySimple));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, STRING));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(trySimple);
        emitStringConcat(il, new AloadPart(0), new StringPart("."), new AloadPart(1));
        il.add(new VarInsnNode(Opcodes.ASTORE, 5));
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$methodMap", MAP_DESC));
        il.add(new VarInsnNode(Opcodes.ALOAD, 5));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        il.add(new VarInsnNode(Opcodes.ASTORE, 4));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, STRING));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(returnOriginal);
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 5;
        mn.maxLocals = 6;
        return mn;
    }

    private static MethodNode buildPutMethod(String name, String mapField) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, ADD_MAP_DESC, null, null);
        mn.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, mapField, MAP_DESC));
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        mn.instructions.add(new InsnNode(Opcodes.POP));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        mn.maxStack = 3;
        mn.maxLocals = 2;
        return mn;
    }

    private static MethodNode buildAddFieldMappingMethod() {
        return buildPutMethod("jffl$addFieldMapping", "jffl$fieldMap");
    }

    private static MethodNode buildAddMethodMappingMethod() {
        return buildPutMethod("jffl$addMethodMapping", "jffl$methodMap");
    }

    private static MethodNode buildAddMethodDescMappingMethod() {
        return buildPutMethod("jffl$addMethodDescMapping", "jffl$methodDescMap");
    }

    private static MethodNode buildClearMappingsMethod() {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$clearMappings", "()V", null, null);
        String[] maps = {"jffl$fieldMap", "jffl$methodMap", "jffl$methodDescMap"};
        for (String map : maps) {
            mn.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, map, MAP_DESC));
            mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "clear", "()V", true));
        }
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        mn.maxStack = 1;
        mn.maxLocals = 0;
        return mn;
    }

    private static MethodNode buildHasMappingsMethod() {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$hasMappings", "()Z", null, null);
        LabelNode returnTrue = new LabelNode();
        mn.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$fieldMap", MAP_DESC));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "isEmpty", "()Z", true));
        mn.instructions.add(new JumpInsnNode(Opcodes.IFEQ, returnTrue));
        mn.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$methodMap", MAP_DESC));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "isEmpty", "()Z", true));
        mn.instructions.add(new JumpInsnNode(Opcodes.IFEQ, returnTrue));
        mn.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$methodDescMap", MAP_DESC));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "isEmpty", "()Z", true));
        mn.instructions.add(new JumpInsnNode(Opcodes.IFEQ, returnTrue));
        mn.instructions.add(new InsnNode(Opcodes.ICONST_0));
        mn.instructions.add(new InsnNode(Opcodes.IRETURN));
        mn.instructions.add(returnTrue);
        mn.instructions.add(new InsnNode(Opcodes.ICONST_1));
        mn.instructions.add(new InsnNode(Opcodes.IRETURN));
        mn.maxStack = 1;
        mn.maxLocals = 0;
        return mn;
    }

    private static InsnList buildFieldRemapPatch(int classSlot, int nameSlot) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL, "jffl$hasMappings", "()Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new VarInsnNode(Opcodes.ALOAD, classSlot));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CTCLASS, "getName", "()" + STRING_DESC));
        il.add(new VarInsnNode(Opcodes.ALOAD, nameSlot));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL, "jffl$remapField", REMAP_DESC));
        il.add(new VarInsnNode(Opcodes.ASTORE, nameSlot));
        il.add(skip);
        return il;
    }

    private static InsnList buildMethodRemapPatch(int classSlot, int nameSlot) {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL, "jffl$hasMappings", "()Z"));
        il.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        il.add(new VarInsnNode(Opcodes.ALOAD, classSlot));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CTCLASS, "getName", "()" + STRING_DESC));
        il.add(new VarInsnNode(Opcodes.ALOAD, nameSlot));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL, "jffl$remapMethod", REMAP_DESC));
        il.add(new VarInsnNode(Opcodes.ASTORE, nameSlot));
        il.add(skip);
        return il;
    }

    private static MethodNode buildJavacBridge(String methodName, String delegateName) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC, methodName,
                "(" + STRING_DESC + STRING_DESC + STRING_DESC + ")V", null, null);
        InsnList il = mn.instructions;
        emitStringConcat(il, new AloadPart(1), new StringPart("."), new AloadPart(2));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL, delegateName, ADD_MAP_DESC));
        il.add(new InsnNode(Opcodes.RETURN));
        mn.maxStack = 3;
        mn.maxLocals = 4;
        return mn;
    }

    private static MethodNode buildJavacAddMethodOverloadMapping() {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC, "addMethodOverloadMapping",
                "(" + STRING_DESC + STRING_DESC + "I" + STRING_DESC + ")V", null, null);
        InsnList il = mn.instructions;
        emitStringConcat(il, new AloadPart(1), new StringPart("."), new AloadPart(2),
                new StringPart("#"), new IloadPart(3));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL, "jffl$addMethodDescMapping", ADD_MAP_DESC));
        il.add(new InsnNode(Opcodes.RETURN));
        mn.maxStack = 5;
        mn.maxLocals = 5;
        return mn;
    }

    private static MethodNode buildJavacLoadMappings() {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "loadMappings", "(Ljava/io/InputStream;)V", null, new String[]{"java/lang/Exception"});
        InsnList il = mn.instructions;
        LabelNode tryStart = new LabelNode(), tryEnd = new LabelNode(), catchLabel = new LabelNode();
        il.add(tryStart);
        il.add(new LdcInsnNode("net.wertygh.jffl.engine.MappingLoader"));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(" + STRING_DESC + ")Ljava/lang/Class;"));
        il.add(new VarInsnNode(Opcodes.ASTORE, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new LdcInsnNode("loadFromStream"));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new LdcInsnNode("java.io.InputStream"));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(" + STRING_DESC + ")Ljava/lang/Class;"));
        il.add(new InsnNode(Opcodes.AASTORE));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod",
                "(" + STRING_DESC + "[Ljava/lang/Class;)Ljava/lang/reflect/Method;"));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new InsnNode(Opcodes.ACONST_NULL));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new InsnNode(Opcodes.AASTORE));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"));
        il.add(new InsnNode(Opcodes.POP));
        il.add(tryEnd);
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(catchLabel);
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));
        il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new LdcInsnNode("JFFL MappingLoader not available"));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>",
                "(" + STRING_DESC + "Ljava/lang/Throwable;)V"));
        il.add(new InsnNode(Opcodes.ATHROW));
        mn.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchLabel, "java/lang/Exception"));
        mn.maxStack = 6;
        mn.maxLocals = 4;
        return mn;
    }

    private static MethodNode buildJavacLoadMappingsFromFile() {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "loadMappingsFromFile", "(" + STRING_DESC + ")V", null, new String[]{"java/lang/Exception"});
        InsnList il = mn.instructions;
        LabelNode tryStart = new LabelNode(), tryEnd = new LabelNode(), catchLabel = new LabelNode();
        il.add(tryStart);
        il.add(new LdcInsnNode("net.wertygh.jffl.engine.MappingLoader"));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(" + STRING_DESC + ")Ljava/lang/Class;"));
        il.add(new VarInsnNode(Opcodes.ASTORE, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new LdcInsnNode("loadFromFile"));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new LdcInsnNode("java.io.File"));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(" + STRING_DESC + ")Ljava/lang/Class;"));
        il.add(new InsnNode(Opcodes.AASTORE));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod",
                "(" + STRING_DESC + "[Ljava/lang/Class;)Ljava/lang/reflect/Method;"));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new InsnNode(Opcodes.ACONST_NULL));
        il.add(new InsnNode(Opcodes.ICONST_1));
        il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new TypeInsnNode(Opcodes.NEW, "java/io/File"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/File", "<init>",
                "(" + STRING_DESC + ")V"));
        il.add(new InsnNode(Opcodes.AASTORE));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"));
        il.add(new InsnNode(Opcodes.POP));
        il.add(tryEnd);
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(catchLabel);
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));
        il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new LdcInsnNode("JFFL MappingLoader not available"));
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>",
                "(" + STRING_DESC + "Ljava/lang/Throwable;)V"));
        il.add(new InsnNode(Opcodes.ATHROW));
        mn.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchLabel, "java/lang/Exception"));
        mn.maxStack = 7;
        mn.maxLocals = 4;
        return mn;
    }

    private static MethodNode buildJavacAutoDiscoverMappings() {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "autoDiscoverMappings", "()V", null, null);
        InsnList il = mn.instructions;
        LabelNode tryStart = new LabelNode(), tryEnd = new LabelNode(), catchLabel = new LabelNode();
        il.add(tryStart);
        il.add(new LdcInsnNode("net.wertygh.jffl.engine.MappingLoader"));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(" + STRING_DESC + ")Ljava/lang/Class;"));
        il.add(new VarInsnNode(Opcodes.ASTORE, 0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new LdcInsnNode("autoDiscover"));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod",
                "(" + STRING_DESC + "[Ljava/lang/Class;)Ljava/lang/reflect/Method;"));
        il.add(new VarInsnNode(Opcodes.ASTORE, 1));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new InsnNode(Opcodes.ACONST_NULL));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"));
        il.add(new InsnNode(Opcodes.POP));
        il.add(tryEnd);
        il.add(new InsnNode(Opcodes.RETURN));
        il.add(catchLabel);
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        il.add(new InsnNode(Opcodes.RETURN));
        mn.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchLabel, "java/lang/Exception"));
        mn.maxStack = 4;
        mn.maxLocals = 3;
        return mn;
    }

    private static void patchFieldAccessForRemap(MethodNode method, String thisClassOwner) {
        MethodInsnNode memberGet = findMethodCall(method, Opcodes.INVOKEVIRTUAL, MEMBER_AST, "get", "()" + STRING_DESC);
        if (memberGet == null) memberGet = findMethodCall(method, Opcodes.INVOKEVIRTUAL, SYMBOL_AST, "get", "()" + STRING_DESC);
        if (memberGet == null) {
            System.out.println("[JavassistPatcher] 未能在 " + thisClassOwner + ".fieldAccess 中找到 Member.get()");
            return;
        }
        VarInsnNode astore = findNextAstore(memberGet);
        if (astore == null) return;
        int nameSlot = astore.var;
        InsnList patch = new InsnList();
        patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        patch.add(new FieldInsnNode(Opcodes.GETFIELD, thisClassOwner, "thisClass", "L" + CTCLASS + ";"));
        patch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CTCLASS, "getName", "()" + STRING_DESC));
        patch.add(new VarInsnNode(Opcodes.ALOAD, nameSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL, "jffl$remapField", REMAP_DESC));
        patch.add(new VarInsnNode(Opcodes.ASTORE, nameSlot));
        method.instructions.insert(astore, patch);
    }

    private static void patchAtCallExprForMethodRemap(MethodNode method, String thisOwner,
                                                      String callCoreName, String callCoreDesc,
                                                      int mnameSlot, int targetClassSlot, int argsSlot) {
        MethodInsnNode callCore = findMethodCall(method, Opcodes.INVOKEVIRTUAL, thisOwner, callCoreName, callCoreDesc);
        if (callCore == null) return;
        AbstractInsnNode insertPoint = findPrecedingAload0(callCore);
        if (insertPoint == null) return;
        InsnList patch = new InsnList();
        LabelNode skip = new LabelNode();
        patch.add(new VarInsnNode(Opcodes.ALOAD, targetClassSlot));
        patch.add(new JumpInsnNode(Opcodes.IFNULL, skip));
        patch.add(new VarInsnNode(Opcodes.ALOAD, targetClassSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CTCLASS, "getName", "()" + STRING_DESC));
        patch.add(new VarInsnNode(Opcodes.ALOAD, mnameSlot));
        patch.add(new VarInsnNode(Opcodes.ALOAD, argsSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ASTLIST, "length", "(L" + ASTLIST + ";)I"));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL, "jffl$remapMethodOverload", REMAP_OVL_DESC));
        patch.add(new VarInsnNode(Opcodes.ASTORE, mnameSlot));
        patch.add(skip);
        method.instructions.insertBefore(insertPoint, patch);
    }

    private static void patchTcAtCallExprForMethodRemap(MethodNode method, int mnameSlot, int targetClassSlot, int argsSlot) {
        String tcCallCoreDesc = "(L" + CTCLASS + ";" + STRING_DESC +
                "Ljavassist/compiler/ast/ASTList;)" +
                "Ljavassist/compiler/MemberResolver$Method;";
        MethodInsnNode callCore = findMethodCall(method, Opcodes.INVOKEVIRTUAL, TC_INTERNAL, "atMethodCallCore", tcCallCoreDesc);
        if (callCore == null) return;
        AbstractInsnNode insertPoint = findPrecedingAload0(callCore);
        if (insertPoint == null) return;
        InsnList patch = new InsnList();
        LabelNode skip = new LabelNode();
        patch.add(new VarInsnNode(Opcodes.ALOAD, targetClassSlot));
        patch.add(new JumpInsnNode(Opcodes.IFNULL, skip));
        patch.add(new VarInsnNode(Opcodes.ALOAD, targetClassSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CTCLASS, "getName", "()" + STRING_DESC));
        patch.add(new VarInsnNode(Opcodes.ALOAD, mnameSlot));
        patch.add(new VarInsnNode(Opcodes.ALOAD, argsSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ASTLIST, "length", "(L" + ASTLIST + ";)I"));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL, "jffl$remapMethodOverload", REMAP_OVL_DESC));
        patch.add(new VarInsnNode(Opcodes.ASTORE, mnameSlot));
        patch.add(skip);
        method.instructions.insertBefore(insertPoint, patch);
    }
}
