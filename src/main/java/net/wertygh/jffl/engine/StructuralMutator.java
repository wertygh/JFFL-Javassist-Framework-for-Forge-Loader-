package net.wertygh.jffl.engine;

import net.wertygh.jffl.api.IClassPatch;
import net.wertygh.jffl.api.annotation.Accessor;
import net.wertygh.jffl.api.annotation.Shadow;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.Modifier;
import javassist.bytecode.Bytecode;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.Descriptor;
import javassist.bytecode.MethodInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

public final class StructuralMutator {
    private static final Logger LOGGER = LoggerFactory.getLogger(StructuralMutator.class);

    public static void generateAccessor(CtClass ctClass, Accessor acc) throws Exception {
        if (acc.invoker()) {
            CtMethod priv = !acc.desc().isEmpty()
                    ? EngineUtils.findMethod(ctClass, acc.target(), acc.desc())
                    : ctClass.getDeclaredMethod(acc.target());
            String bridgeName = "jffl$invoke$" + acc.target();
            if (EngineUtils.hasMethod(ctClass, bridgeName)) return;
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
            if (!EngineUtils.hasMethod(ctClass, getName)) {
                ctClass.addMethod(CtNewMethod.make(
                        "public " + f.getType().getName() + " " + getName + "() { return this." + acc.target() + "; }",
                        ctClass));
            }
            if (!EngineUtils.hasMethod(ctClass, setName)) {
                ctClass.addMethod(CtNewMethod.make(
                        "public void " + setName + "(" + f.getType().getName() + " v) { this." + acc.target() + " = v; }",
                        ctClass));
            }
        }
    }

    public static void generateShadow(CtClass ctClass, IClassPatch patch, Method patchMethod, Shadow sh) throws Exception {
        if (sh.invoker()) {
            CtMethod priv = !sh.desc().isEmpty()
                    ? EngineUtils.findMethod(ctClass, sh.target(), sh.desc())
                    : ctClass.getDeclaredMethod(sh.target());
            String bridgeName = "jffl$shadow$invoke$" + sh.target();
            if (EngineUtils.hasMethod(ctClass, bridgeName)) return;
            boolean isStatic = (priv.getModifiers() & Modifier.STATIC) != 0;
            StringBuilder sb = new StringBuilder("public ");
            if (isStatic) sb.append("static ");
            sb.append(priv.getReturnType().getName()).append(' ').append(bridgeName).append('(');
            CtClass[] params = priv.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getName()).append(" arg").append(i);
            }
            sb.append(") { ");
            if (priv.getReturnType() != CtClass.voidType) sb.append("return ");
            if (isStatic) sb.append(ctClass.getName()).append('.');
            sb.append(sh.target()).append('(');
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append("arg").append(i);
            }
            sb.append("); }");
            ctClass.addMethod(CtNewMethod.make(sb.toString(), ctClass));
            LOGGER.debug("@Shadow invoker: 生成 {}.{}", ctClass.getName(), bridgeName);
        } else {
            CtField f = ctClass.getDeclaredField(sh.target());
            String getName = "jffl$shadow$" + sh.target() + "$get";
            String setName = "jffl$shadow$" + sh.target() + "$set";
            String tn = f.getType().getName();
            if (!EngineUtils.hasMethod(ctClass, getName)) {
                ctClass.addMethod(CtNewMethod.make(
                        "public " + tn + " " + getName + "() { return this." + sh.target() + "; }",
                        ctClass));
            }
            if (!EngineUtils.hasMethod(ctClass, setName)) {
                ctClass.addMethod(CtNewMethod.make(
                        "public void " + setName + "(" + tn + " v) { this." + sh.target() + " = v; }",
                        ctClass));
            }
            LOGGER.debug("@Shadow field: 生成 {}.{}/{}", ctClass.getName(), getName, setName);
        }
    }

    public static void insertBeforeSuperCall(CtConstructor ctor, String src) throws Exception {
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
        if (type == CtClass.intType || type == CtClass.booleanType
                || type == CtClass.byteType || type == CtClass.charType
                || type == CtClass.shortType) {
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
}
