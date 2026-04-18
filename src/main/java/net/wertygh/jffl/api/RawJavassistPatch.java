package net.wertygh.jffl.api;

import javassist.CtClass;

public interface RawJavassistPatch {
    void transform(CtClass ctClass, PatchContext ctx) throws Exception;
}