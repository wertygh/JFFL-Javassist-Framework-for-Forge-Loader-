package net.wertygh.jffl.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface PatchState {
    String name() default "";
    boolean targetField() default false;
    String initializer() default "";
    int access() default 0;
}
