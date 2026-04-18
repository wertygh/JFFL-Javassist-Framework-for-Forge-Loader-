package net.wertygh.jffl.engine;

import net.wertygh.jffl.api.annotation.ConditionalPatch;
import javassist.ClassPool;
import javassist.CtClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConditionEvaluator {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConditionEvaluator.class);

    public static boolean shouldApply(Class<?> patchClass, ClassPool pool, CtClass targetClass) {
        ConditionalPatch[] conditions = patchClass.getAnnotationsByType(ConditionalPatch.class);
        if (conditions.length == 0) return true;
        for (ConditionalPatch cond : conditions) {
            boolean result = evaluate(cond, pool, targetClass);
            if (cond.negate()) result = !result;
            if (!result) return false;
        }
        return true;
    }

    private static boolean evaluate(ConditionalPatch cond, ClassPool pool, CtClass targetClass) {
        return switch (cond.type()) {
            case CLASS_EXISTS -> {
                try {
                    pool.get(cond.value());
                    yield true;
                } catch (Exception e) {
                    yield false;
                }
            }
            case SYSTEM_PROPERTY -> {
                String val = System.getProperty(cond.value(), "");
                yield cond.expect().isEmpty() ? !val.isEmpty() : cond.expect().equals(val);
            }
            case ENV_VAR -> {
                String val = System.getenv(cond.value());
                if (val == null) val = "";
                yield cond.expect().isEmpty() ? !val.isEmpty() : cond.expect().equals(val);
            }
            case METHOD_EXISTS -> {
                try {
                    targetClass.getDeclaredMethod(cond.value());
                    yield true;
                } catch (Exception e) {
                    yield false;
                }
            }
        };
    }
}