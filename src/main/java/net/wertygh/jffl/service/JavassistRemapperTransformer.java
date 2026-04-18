package net.wertygh.jffl.service;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class JavassistRemapperTransformer implements ITransformer<ClassNode> {
    private static final Logger LOGGER = LoggerFactory.getLogger(JavassistRemapperTransformer.class);
    private static final String REMAP_OVL_DESC = "(Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;";
    private static final String REMAP_DESC = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
    private static final String ADD_MAP_DESC = "(Ljava/lang/String;Ljava/lang/String;)V";
    private static final String CHM_IMPL = "java/util/concurrent/ConcurrentHashMap";
    private static final String MCG_INTERNAL = "javassist/compiler/MemberCodeGen";
    private static final String CTCLASSTYPE_INTERNAL = "javassist/CtClassType";
    private static final String TC_INTERNAL = "javassist/compiler/TypeChecker";
    private static final String MEMBER_AST = "javassist/compiler/ast/Member";
    private static final String SYMBOL_AST = "javassist/compiler/ast/Symbol";
    private static final String JAVAC_INTERNAL = "javassist/compiler/Javac";
    private static final String ASTLIST = "javassist/compiler/ast/ASTList";
    private static final String SB_INTERNAL = "java/lang/StringBuilder";
    private static final String SB_DESC = "Ljava/lang/StringBuilder;";
    private static final String STRING_DESC = "Ljava/lang/String;";
    private static final String CTCLASS = "javassist/CtClass";
    private static final String MAP_DESC = "Ljava/util/Map;";
    private static final String MAP_IFACE = "java/util/Map";
    private static final String STRING = "java/lang/String";
    private static final String MCG_DOT = "javassist.compiler.MemberCodeGen";
    private static final String TC_DOT = "javassist.compiler.TypeChecker";
    private static final String JAVAC_DOT = "javassist.compiler.Javac";
    private static final String CTC_DOT = "javassist.CtClassType";

    private static final Set<Target> TARGETS = Set.of(
            Target.targetClass(MCG_DOT),
            Target.targetClass(TC_DOT),
            Target.targetClass(JAVAC_DOT),
            Target.targetClass(CTC_DOT)
    );

    @Override
    public @NotNull ClassNode transform(@NotNull ClassNode input, @NotNull ITransformerVotingContext context) {
        String name = input.name;
        if (MCG_INTERNAL.equals(name)) {
            return transformMemberCodeGen(input);
        } else if (TC_INTERNAL.equals(name)) {
            return transformTypeChecker(input);
        } else if (JAVAC_INTERNAL.equals(name)) {
            return transformJavac(input);
        } else if (CTCLASSTYPE_INTERNAL.equals(name)) {
            return transformCtClassType(input);
        }
        return input;
    }

    @Override
    public @NotNull TransformerVoteResult castVote(@NotNull ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    public @NotNull Set<Target> targets() {
        return TARGETS;
    }

    @Override
    public String[] labels() {
        return new String[]{"jffl-javassist-remapper"};
    }

    private ClassNode transformMemberCodeGen(ClassNode classNode) {
        LOGGER.info("转换javassist.compiler.MemberCodeGen");
        classNode.fields.add(new FieldNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$fieldMap", MAP_DESC, null, null));
        classNode.fields.add(new FieldNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$methodMap", MAP_DESC, null, null));
        classNode.fields.add(new FieldNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$methodDescMap", MAP_DESC, null, null));
        MethodNode clinit = findMethod(classNode, "<clinit>", "()V");
        InsnList initInsns = buildMapInitInsns();
        if (clinit != null) {
            AbstractInsnNode first = clinit.instructions.getFirst();
            if (first != null) {
                clinit.instructions.insertBefore(first, initInsns);
            } else {
                clinit.instructions.insert(initInsns);
            }
            if (clinit.maxStack < 2) clinit.maxStack = 2;
        } else {
            clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            InsnList ci = buildMapInitInsns();
            ci.add(new InsnNode(Opcodes.RETURN));
            clinit.instructions = ci;
            clinit.maxStack = 2;
            clinit.maxLocals = 0;
            classNode.methods.add(clinit);
        }
        classNode.methods.add(buildRemapFieldMethod());
        classNode.methods.add(buildRemapMethodMethod());
        classNode.methods.add(buildRemapMethodOverload());
        classNode.methods.add(buildAddFieldMappingMethod());
        classNode.methods.add(buildAddMethodMappingMethod());
        classNode.methods.add(buildAddMethodDescMappingMethod());
        classNode.methods.add(buildClearMappingsMethod());
        classNode.methods.add(buildHasMappingsMethod());
        MethodNode fieldAccessMethod = findMethod(classNode, "fieldAccess",
                "(Ljavassist/compiler/ast/ASTree;Z)Ljavassist/CtField;");
        if (fieldAccessMethod == null) {
            fieldAccessMethod = findMethod(classNode, "fieldAccess", null);
        }
        if (fieldAccessMethod != null) {
            patchFieldAccessForRemap(fieldAccessMethod, MCG_INTERNAL);
            patchFieldAccessExprBranches(fieldAccessMethod, MCG_INTERNAL);
        } else {
            LOGGER.warn("找不到MemberCodeGen.fieldAccess");
        }
        MethodNode atCallExprMethod = findMethod(classNode, "atCallExpr",
                "(Ljavassist/compiler/ast/CallExpr;)V");
        if (atCallExprMethod != null) {
            String mcgCallCoreDesc =
                    "(L" + CTCLASS + ";" + STRING_DESC +
                    "Ljavassist/compiler/ast/ASTList;" +
                    "ZZI" +
                    "Ljavassist/compiler/MemberResolver$Method;)V";
            int argsSlot = findArgsSlot(atCallExprMethod);
            patchAtCallExprForMethodRemap(
                    atCallExprMethod, MCG_INTERNAL,
                    "atMethodCallCore", mcgCallCoreDesc,
                    /* mnameSlot */ 2, /* targetClassSlot */ 3, /* argsSlot */ argsSlot);
        } else {
            LOGGER.warn("找不到MemberCodeGen.atCallExpr");
        }
        return classNode;
    }

    private ClassNode transformTypeChecker(ClassNode classNode) {
        LOGGER.info("转换javassist.compiler.TypeChecker");
        MethodNode fieldAccessMethod = findMethod(classNode, "fieldAccess", null);
        if (fieldAccessMethod != null) {
            patchFieldAccessForRemap(fieldAccessMethod, TC_INTERNAL);
        } else {
            LOGGER.warn("找不到TypeChecker.fieldAccess");
        }
        MethodNode atCallExprMethod = findMethod(classNode, "atCallExpr",
                "(Ljavassist/compiler/ast/CallExpr;)V");
        if (atCallExprMethod != null) {
            int argsSlot = findArgsSlot(atCallExprMethod);
            patchTcAtCallExprForMethodRemap(atCallExprMethod,
                    /* mnameSlot */ 2, /* targetClassSlot */ 3, /* argsSlot */ argsSlot);
        } else {
            LOGGER.warn("找不到TypeChecker.atCallExpr");
        }
        return classNode;
    }

    private ClassNode transformJavac(ClassNode classNode) {
        LOGGER.info("转换javassist.compiler.Javac");
        classNode.methods.add(buildJavacBridge("addFieldMapping","jffl$addFieldMapping"));
        classNode.methods.add(buildJavacBridge("addMethodMapping","jffl$addMethodMapping"));
        classNode.methods.add(buildJavacBridge("addMethodDescMapping", "jffl$addMethodDescMapping"));
        classNode.methods.add(buildJavacAddMethodOverloadMapping());
        classNode.methods.add(buildJavacLoadMappings());
        classNode.methods.add(buildJavacLoadMappingsFromFile());
        classNode.methods.add(buildJavacAutoDiscoverMappings());
        return classNode;
    }

    private ClassNode transformCtClassType(ClassNode classNode) {
        LOGGER.info("转换javassist.CtClassType");
        MethodNode gdf2 = findMethod(classNode, "getDeclaredField2",
                "(" + STRING_DESC + STRING_DESC + ")Ljavassist/CtField;");
        if (gdf2 != null) {
            AbstractInsnNode first = gdf2.instructions.getFirst();
            if (first != null) {
                gdf2.instructions.insertBefore(first, buildFieldRemapPatch(0, 1));
                if (gdf2.maxStack < 3) gdf2.maxStack = 3;
            }
        } else {
            LOGGER.warn("找不到CtClassType.getDeclaredField2");
        }
        MethodNode gm0 = findMethod(classNode, "getMethod0",
                "(Ljavassist/CtClass;"+STRING_DESC+STRING_DESC+")Ljavassist/CtMethod;");
        if (gm0 != null) {
            AbstractInsnNode first = gm0.instructions.getFirst();
            if (first != null) {
                gm0.instructions.insertBefore(first, buildMethodRemapPatch(0, 1));
                if (gm0.maxStack < 3) gm0.maxStack = 3;
            }
        } else {
            LOGGER.warn("找不到CtClassType.getMethod0");
        }
        MethodNode gdm1 = findMethod(classNode, "getDeclaredMethod",
                "(" + STRING_DESC + ")Ljavassist/CtMethod;");
        if (gdm1 != null) {
            AbstractInsnNode first = gdm1.instructions.getFirst();
            if (first != null) {
                gdm1.instructions.insertBefore(first, buildMethodRemapPatch(0, 1));
                if (gdm1.maxStack < 3) gdm1.maxStack = 3;
            }
        } else {
            LOGGER.warn("找不到CtClassType.getDeclaredMethod(String)");
        }
        MethodNode gdm2 = findMethod(classNode, "getDeclaredMethod",
                "(" + STRING_DESC + "[Ljavassist/CtClass;)Ljavassist/CtMethod;");
        if (gdm2 != null) {
            AbstractInsnNode first = gdm2.instructions.getFirst();
            if (first != null) {
                gdm2.instructions.insertBefore(first, buildMethodRemapPatch(0, 1));
                if (gdm2.maxStack < 3) gdm2.maxStack = 3;
            }
        } else {
            LOGGER.warn("找不到CtClassType.getDeclaredMethod(String, CtClass[])");
        }
        MethodNode gdms = findMethod(classNode, "getDeclaredMethods",
                "(" + STRING_DESC + ")[Ljavassist/CtMethod;");
        if (gdms != null) {
            AbstractInsnNode first = gdms.instructions.getFirst();
            if (first != null) {
                gdms.instructions.insertBefore(first, buildMethodRemapPatch(0, 1));
                if (gdms.maxStack < 3) gdms.maxStack = 3;
            }
        } else {
            LOGGER.warn("找不到CtClassType.getDeclaredMethods(String)");
        }
        return classNode;
    }

    private static MethodNode findMethod(ClassNode classNode, String name, String desc) {
        for (MethodNode mn : classNode.methods) {
            if (mn.name.equals(name) && (desc == null || mn.desc.equals(desc))) {
                return mn;
            }
        }
        return null;
    }

    private static MethodInsnNode findMethodCall(MethodNode methodNode, int opcode, String owner, String name, String desc) {
        for (Iterator<AbstractInsnNode> it = methodNode.instructions.iterator(); it.hasNext();) {
            AbstractInsnNode insn = it.next();
            if (insn.getOpcode() == opcode && insn instanceof MethodInsnNode min
                    && min.owner.equals(owner)
                    && min.name.equals(name)
                    && min.desc.equals(desc)) {
                return min;
            }
        }
        return null;
    }

    private static List<MethodInsnNode> findAllMethodCalls(MethodNode methodNode, int opcode, String owner, String name, String desc) {
        List<MethodInsnNode> results = new ArrayList<>();
        for (Iterator<AbstractInsnNode> it = methodNode.instructions.iterator(); it.hasNext();) {
            AbstractInsnNode insn = it.next();
            if (insn.getOpcode() == opcode && insn instanceof MethodInsnNode min
                    && min.owner.equals(owner)
                    && min.name.equals(name)
                    && min.desc.equals(desc)) {
                results.add(min);
            }
        }
        return results;
    }

    private static AbstractInsnNode findPrecedingAload0(AbstractInsnNode startInsn) {
        AbstractInsnNode cur = startInsn.getPrevious();
        while (cur != null) {
            int type = cur.getType();
            if (type == AbstractInsnNode.JUMP_INSN) break;
            if (cur.getOpcode() == Opcodes.ALOAD
                    && cur instanceof VarInsnNode v
                    && v.var == 0) {
                return cur;
            }
            cur = cur.getPrevious();
        }
        return null;
    }

    private static VarInsnNode findNextAstore(AbstractInsnNode startInsn) {
        AbstractInsnNode cur = startInsn.getNext();
        while (cur != null) {
            if (cur.getOpcode() == Opcodes.ASTORE&&cur instanceof VarInsnNode v) return v;
            cur = cur.getNext();
        }
        return null;
    }

    private static int findArgsSlot(MethodNode methodNode) {
        for (Iterator<AbstractInsnNode> it = methodNode.instructions.iterator(); it.hasNext();) {
            AbstractInsnNode insn = it.next();
            if (insn.getOpcode() == Opcodes.CHECKCAST
                    && insn instanceof TypeInsnNode tin
                    && ASTLIST.equals(tin.desc)) {
                AbstractInsnNode next = insn.getNext();
                if (next != null && next.getOpcode() == Opcodes.ASTORE
                        && next instanceof VarInsnNode vin) {
                    return vin.var;
                }
            }
        }
        return 5;
    }

    private sealed interface ConcatPart {}
    private record StringPart(String value)  implements ConcatPart {}
    private record AloadPart(int slot) implements ConcatPart {}
    private record IloadPart(int slot) implements ConcatPart {}

    private static void emitStringConcat(InsnList insns, ConcatPart... parts) {
        insns.add(new TypeInsnNode(Opcodes.NEW, SB_INTERNAL));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, SB_INTERNAL, "<init>", "()V", false));
        for (ConcatPart p : parts) {
            if (p instanceof StringPart sp) {
                insns.add(new LdcInsnNode(sp.value));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SB_INTERNAL, "append",
                        "(" + STRING_DESC + ")" + SB_DESC, false));
            } else if (p instanceof AloadPart ap) {
                insns.add(new VarInsnNode(Opcodes.ALOAD, ap.slot));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SB_INTERNAL, "append",
                        "(" + STRING_DESC + ")" + SB_DESC, false));
            } else if (p instanceof IloadPart ip) {
                insns.add(new VarInsnNode(Opcodes.ILOAD, ip.slot));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SB_INTERNAL, "append",
                        "(I)" + SB_DESC, false));
            }
        }
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SB_INTERNAL, "toString",
                "()" + STRING_DESC, false));
    }

    private static InsnList buildMapInitInsns() {
        InsnList insns = new InsnList();
        String[] maps = {"jffl$fieldMap", "jffl$methodMap", "jffl$methodDescMap"};
        for (String map : maps) {
            insns.add(new TypeInsnNode(Opcodes.NEW, CHM_IMPL));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CHM_IMPL, "<init>", "()V", false));
            insns.add(new FieldInsnNode(Opcodes.PUTSTATIC, MCG_INTERNAL, map, MAP_DESC));
        }
        return insns;
    }

    private static MethodNode buildRemapFieldMethod() {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$remapField", REMAP_DESC, null, null);
        InsnList insns = new InsnList();
        LabelNode returnOriginal = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        emitStringConcat(insns, new AloadPart(0), new StringPart("."), new AloadPart(1));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 2));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$fieldMap", MAP_DESC));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, STRING));
        insns.add(new InsnNode(Opcodes.ARETURN));
        insns.add(returnOriginal);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new InsnNode(Opcodes.ARETURN));
        mn.instructions = insns;
        mn.maxStack = 3;
        mn.maxLocals = 4;
        return mn;
    }

    private static MethodNode buildRemapMethodMethod() {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$remapMethod", REMAP_DESC, null, null);
        InsnList insns = new InsnList();
        LabelNode returnOriginal = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        emitStringConcat(insns, new AloadPart(0), new StringPart("."), new AloadPart(1));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 2));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$methodMap", MAP_DESC));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, STRING));
        insns.add(new InsnNode(Opcodes.ARETURN));
        insns.add(returnOriginal);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new InsnNode(Opcodes.ARETURN));
        mn.instructions = insns;
        mn.maxStack = 3;
        mn.maxLocals = 4;
        return mn;
    }

    private static MethodNode buildRemapMethodOverload() {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "jffl$remapMethodOverload", REMAP_OVL_DESC, null, null);
        InsnList insns = new InsnList();
        LabelNode trySimple = new LabelNode();
        LabelNode returnOriginal = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        emitStringConcat(insns,
                new AloadPart(0), new StringPart("."),
                new AloadPart(1), new StringPart("#"),
                new IloadPart(2));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$methodDescMap", MAP_DESC));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 4));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, trySimple));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, STRING));
        insns.add(new InsnNode(Opcodes.ARETURN));
        insns.add(trySimple);
        emitStringConcat(insns, new AloadPart(0), new StringPart("."), new AloadPart(1));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 5));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$methodMap", MAP_DESC));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 4));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, returnOriginal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, STRING));
        insns.add(new InsnNode(Opcodes.ARETURN));
        insns.add(returnOriginal);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new InsnNode(Opcodes.ARETURN));
        mn.instructions = insns;
        mn.maxStack = 5;
        mn.maxLocals = 6;
        return mn;
    }

    private static MethodNode buildPutMethod(String name, String mapField) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, ADD_MAP_DESC, null, null);
        InsnList insns = new InsnList();
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, mapField, MAP_DESC));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new InsnNode(Opcodes.RETURN));
        mn.instructions = insns;
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
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "jffl$clearMappings", "()V", null, null);
        InsnList insns = new InsnList();
        String[] maps = {"jffl$fieldMap", "jffl$methodMap", "jffl$methodDescMap"};
        for (String map : maps) {
            insns.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, map, MAP_DESC));
            insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "clear", "()V", true));
        }
        insns.add(new InsnNode(Opcodes.RETURN));
        mn.instructions = insns;
        mn.maxStack = 1;
        mn.maxLocals = 0;
        return mn;
    }

    private static MethodNode buildHasMappingsMethod() {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "jffl$hasMappings", "()Z", null, null);
        InsnList insns = new InsnList();
        LabelNode returnTrue = new LabelNode();
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$fieldMap", MAP_DESC));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "isEmpty", "()Z", true));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, returnTrue));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$methodMap", MAP_DESC));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "isEmpty", "()Z", true));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, returnTrue));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, MCG_INTERNAL, "jffl$methodDescMap", MAP_DESC));
        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_IFACE, "isEmpty", "()Z", true));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, returnTrue));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new InsnNode(Opcodes.IRETURN));
        insns.add(returnTrue);
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IRETURN));
        mn.instructions = insns;
        mn.maxStack = 1;
        mn.maxLocals = 0;
        return mn;
    }

    private static InsnList buildFieldRemapPatch(int thisOrClassSlot, int nameSlot) {
        InsnList patch = new InsnList();
        LabelNode skipLabel = new LabelNode();
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL,
                "jffl$hasMappings", "()Z", false));
        patch.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));
        patch.add(new VarInsnNode(Opcodes.ALOAD, thisOrClassSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CTCLASS,
                "getName", "()" + STRING_DESC, false));
        patch.add(new VarInsnNode(Opcodes.ALOAD, nameSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL,
                "jffl$remapField", REMAP_DESC, false));
        patch.add(new VarInsnNode(Opcodes.ASTORE, nameSlot));
        patch.add(skipLabel);
        return patch;
    }

    private static InsnList buildMethodRemapPatch(int thisOrClassSlot, int nameSlot) {
        InsnList patch = new InsnList();
        LabelNode skipLabel = new LabelNode();
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL,
                "jffl$hasMappings", "()Z", false));
        patch.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));
        patch.add(new VarInsnNode(Opcodes.ALOAD, thisOrClassSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CTCLASS,
                "getName", "()" + STRING_DESC, false));
        patch.add(new VarInsnNode(Opcodes.ALOAD, nameSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL,
                "jffl$remapMethod", REMAP_DESC, false));
        patch.add(new VarInsnNode(Opcodes.ASTORE, nameSlot));
        patch.add(skipLabel);
        return patch;
    }

    private static MethodNode buildJavacBridge(String methodName, String delegateName) {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC, methodName,
                "(" + STRING_DESC + STRING_DESC + STRING_DESC + ")V", null, null);
        InsnList insns = new InsnList();
        emitStringConcat(insns, new AloadPart(1), new StringPart("."), new AloadPart(2));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL,
                delegateName, ADD_MAP_DESC, false));
        insns.add(new InsnNode(Opcodes.RETURN));
        mn.instructions = insns;
        mn.maxStack = 3;
        mn.maxLocals = 4;
        return mn;
    }

    private static MethodNode buildJavacAddMethodOverloadMapping() {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC, "addMethodOverloadMapping",
                "(" + STRING_DESC + STRING_DESC + "I" + STRING_DESC + ")V", null, null);
        InsnList insns = new InsnList();
        emitStringConcat(insns,
                new AloadPart(1), new StringPart("."),
                new AloadPart(2), new StringPart("#"),
                new IloadPart(3));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL,
                "jffl$addMethodDescMapping", ADD_MAP_DESC, false));
        insns.add(new InsnNode(Opcodes.RETURN));
        mn.instructions = insns;
        mn.maxStack = 5;
        mn.maxLocals = 5;
        return mn;
    }

    private static MethodNode buildJavacLoadMappings() {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "loadMappings",
                "(Ljava/io/InputStream;)V", null, new String[]{"java/lang/Exception"});
        InsnList insns = new InsnList();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode catchLabel = new LabelNode();
        insns.add(tryStart);
        insns.add(new LdcInsnNode("net.wertygh.jffl.engine.MappingLoader"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(" + STRING_DESC + ")Ljava/lang/Class;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new LdcInsnNode("loadFromStream"));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new LdcInsnNode("java.io.InputStream"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(" + STRING_DESC + ")Ljava/lang/Class;", false));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", 
                "getMethod","(" + STRING_DESC + "[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 2));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new InsnNode(Opcodes.ACONST_NULL));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", 
                 "invoke","(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",false));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(tryEnd);
        insns.add(new InsnNode(Opcodes.RETURN));
        insns.add(catchLabel);
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new LdcInsnNode("JFFL的MappingLoader不可用, 你干啥了?"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException",
                "<init>", "(" + STRING_DESC + "Ljava/lang/Throwable;)V", false));
        insns.add(new InsnNode(Opcodes.ATHROW));
        mn.instructions = insns;
        mn.tryCatchBlocks = new ArrayList<>();
        mn.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchLabel, "java/lang/Exception"));
        mn.maxStack = 6;
        mn.maxLocals = 4;
        return mn;
    }

    private static MethodNode buildJavacLoadMappingsFromFile() {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "loadMappingsFromFile",
                "(" + STRING_DESC + ")V", null, new String[]{"java/lang/Exception"});
        InsnList insns = new InsnList();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode catchLabel = new LabelNode();
        insns.add(tryStart);
        insns.add(new LdcInsnNode("net.wertygh.jffl.engine.MappingLoader"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(" + STRING_DESC + ")Ljava/lang/Class;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new LdcInsnNode("loadFromFile"));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new LdcInsnNode("java.io.File"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(" + STRING_DESC + ")Ljava/lang/Class;", false));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", 
                "getMethod", "(" + STRING_DESC + "[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 2));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new InsnNode(Opcodes.ACONST_NULL));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/io/File"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/File", "<init>",
                "(" + STRING_DESC + ")V", false));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", 
                "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(tryEnd);
        insns.add(new InsnNode(Opcodes.RETURN));
        insns.add(catchLabel);
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new LdcInsnNode("JFFL MappingLoader not available"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException",
                "<init>", "(" + STRING_DESC + "Ljava/lang/Throwable;)V", false));
        insns.add(new InsnNode(Opcodes.ATHROW));
        mn.instructions = insns;
        mn.tryCatchBlocks = new ArrayList<>();
        mn.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchLabel, "java/lang/Exception"));
        mn.maxStack = 7;
        mn.maxLocals = 4;
        return mn;
    }

    private static MethodNode buildJavacAutoDiscoverMappings() {
        MethodNode mn = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "autoDiscoverMappings",
                "()V", null, null);
        InsnList insns = new InsnList();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode catchLabel = new LabelNode();
        insns.add(tryStart);
        insns.add(new LdcInsnNode("net.wertygh.jffl.engine.MappingLoader"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(" + STRING_DESC + ")Ljava/lang/Class;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new LdcInsnNode("autoDiscover"));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", 
                "getMethod", "(" + STRING_DESC + "[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new InsnNode(Opcodes.ACONST_NULL));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", 
                "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(tryEnd);
        insns.add(new InsnNode(Opcodes.RETURN));
        insns.add(catchLabel);
        insns.add(new VarInsnNode(Opcodes.ASTORE, 2));
        insns.add(new InsnNode(Opcodes.RETURN));
        mn.instructions = insns;
        mn.tryCatchBlocks = new ArrayList<>();
        mn.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, catchLabel, "java/lang/Exception"));
        mn.maxStack = 4;
        mn.maxLocals = 3;
        return mn;
    }

    private static void patchFieldAccessForRemap(MethodNode methodNode, String thisClassOwner) {
        InsnList instructions = methodNode.instructions;
        MethodInsnNode memberGetInsn = findMethodCall(methodNode, Opcodes.INVOKEVIRTUAL,
                MEMBER_AST, "get", "()" + STRING_DESC);
        if (memberGetInsn == null) {
            memberGetInsn = findMethodCall(methodNode, Opcodes.INVOKEVIRTUAL,
                    SYMBOL_AST, "get", "()" + STRING_DESC);
        }
        if (memberGetInsn == null) {
            LOGGER.warn("无法在{}.fieldAccess中找到Member.get()", thisClassOwner);
            return;
        }
        VarInsnNode astoreInsn = findNextAstore(memberGetInsn);
        if (astoreInsn == null) {
            LOGGER.warn("在{}.fieldAccess中, Member.get()之后未找到ASTORE", thisClassOwner);
            return;
        }
        int nameSlot = astoreInsn.var;
        InsnList patch = new InsnList();
        patch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        patch.add(new FieldInsnNode(Opcodes.GETFIELD, thisClassOwner,
                "thisClass", "L" + CTCLASS + ";"));
        patch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CTCLASS,
                "getName", "()" + STRING_DESC, false));
        patch.add(new VarInsnNode(Opcodes.ALOAD, nameSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL,
                "jffl$remapField", REMAP_DESC, false));
        patch.add(new VarInsnNode(Opcodes.ASTORE, nameSlot));
        instructions.insert(astoreInsn, patch);
        LOGGER.info("已修补{}.fieldAccess, 在Member.get()之后注入字段重映射(槽{})",
                thisClassOwner, nameSlot);
    }

    private static void patchFieldAccessExprBranches(MethodNode methodNode, String thisClassOwner) {
        List<MethodInsnNode> lookups = findAllMethodCalls(methodNode, Opcodes.INVOKEVIRTUAL,
                "javassist/compiler/MemberResolver", "lookupField",
                "("+STRING_DESC+"Ljavassist/compiler/ast/Symbol;)Ljavassist/CtField;");
        List<MethodInsnNode> lookupsByJvm = findAllMethodCalls(methodNode, Opcodes.INVOKEVIRTUAL,
                "javassist/compiler/MemberResolver", "lookupFieldByJvmName",
                "("+STRING_DESC+"Ljavassist/compiler/ast/Symbol;)Ljavassist/CtField;");
        int total = lookups.size() + lookupsByJvm.size();
        if (total == 0) {
            LOGGER.debug("在{}.fieldAccess中未找到lookupField调用", thisClassOwner);
            return;
        }
        LOGGER.debug("在{}.fieldAccess的Expr分支中找到{}次lookupField调用, 已被成员分支重映射覆盖", thisClassOwner, total);
    }

    private static void patchAtCallExprForMethodRemap(MethodNode methodNode, String thisClassOwner, String callCoreName, String callCoreDesc, int mnameSlot, int targetClassSlot, int argsSlot) {
        InsnList instructions = methodNode.instructions;
        MethodInsnNode callCoreInsn = findMethodCall(methodNode, Opcodes.INVOKEVIRTUAL,
                thisClassOwner, callCoreName, callCoreDesc);
        if (callCoreInsn == null) {
            LOGGER.warn("无法在{}.atCallExpr中找到{}调用", thisClassOwner, callCoreName);
            return;
        }
        AbstractInsnNode insertPoint = findPrecedingAload0(callCoreInsn);
        if (insertPoint == null) {
            LOGGER.warn("在{}之前未找到ALOAD_0", callCoreName);
            return;
        }
        InsnList patch = new InsnList();
        LabelNode skipLabel = new LabelNode();
        patch.add(new VarInsnNode(Opcodes.ALOAD, targetClassSlot));
        patch.add(new JumpInsnNode(Opcodes.IFNULL, skipLabel));
        patch.add(new VarInsnNode(Opcodes.ALOAD, targetClassSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CTCLASS,
                "getName", "()" + STRING_DESC, false));
        patch.add(new VarInsnNode(Opcodes.ALOAD, mnameSlot));
        patch.add(new VarInsnNode(Opcodes.ALOAD, argsSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ASTLIST,
                "length", "(L" + ASTLIST + ";)I", false));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL,
                "jffl$remapMethodOverload", REMAP_OVL_DESC, false));
        patch.add(new VarInsnNode(Opcodes.ASTORE, mnameSlot));
        patch.add(skipLabel);
        instructions.insertBefore(insertPoint, patch);
        LOGGER.info("注入了{}.atCallExpr, 在{}之前注入了支持重载感知的方法重映射"
                        + "(mname槽{}, targetClass槽{}, args槽{})",
                thisClassOwner, callCoreName, mnameSlot, targetClassSlot, argsSlot);
    }

    private static void patchTcAtCallExprForMethodRemap(MethodNode methodNode,
                                                        int mnameSlot, int targetClassSlot, int argsSlot) {
        InsnList instructions = methodNode.instructions;
        String tcCallCoreDesc =
                "(L" + CTCLASS + ";" + STRING_DESC +
                "Ljavassist/compiler/ast/ASTList;)" +
                "Ljavassist/compiler/MemberResolver$Method;";
        MethodInsnNode callCoreInsn = findMethodCall(methodNode, Opcodes.INVOKEVIRTUAL,
                TC_INTERNAL, "atMethodCallCore", tcCallCoreDesc);
        if (callCoreInsn == null) {
            LOGGER.warn("找不到TypeChecker.atMethodCallCore调用");
            return;
        }
        AbstractInsnNode insertPoint = findPrecedingAload0(callCoreInsn);
        if (insertPoint == null) {
            LOGGER.warn("在TypeChecker.atMethodCallCore之前找不到ALOAD_0");
            return;
        }
        InsnList patch = new InsnList();
        LabelNode skipLabel = new LabelNode();
        patch.add(new VarInsnNode(Opcodes.ALOAD, targetClassSlot));
        patch.add(new JumpInsnNode(Opcodes.IFNULL, skipLabel));
        patch.add(new VarInsnNode(Opcodes.ALOAD, targetClassSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CTCLASS,
                "getName", "()" + STRING_DESC, false));
        patch.add(new VarInsnNode(Opcodes.ALOAD, mnameSlot));
        patch.add(new VarInsnNode(Opcodes.ALOAD, argsSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ASTLIST,
                "length", "(L" + ASTLIST + ";)I", false));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MCG_INTERNAL,
                "jffl$remapMethodOverload", REMAP_OVL_DESC, false));
        patch.add(new VarInsnNode(Opcodes.ASTORE, mnameSlot));
        patch.add(skipLabel);
        instructions.insertBefore(insertPoint, patch);
    }
}
