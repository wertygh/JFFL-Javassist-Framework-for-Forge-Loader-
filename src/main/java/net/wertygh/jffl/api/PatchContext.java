package net.wertygh.jffl.api;

import javassist.ClassPool;


public class PatchContext {
    private final String className;
    private final ClassPool classPool;

    public PatchContext(String className, ClassPool classPool) {
        this.className = className;
        this.classPool = classPool;
    }

    public String getClassName() {
        return className;
    }

    public ClassPool getClassPool() {
        return classPool;
    }
}