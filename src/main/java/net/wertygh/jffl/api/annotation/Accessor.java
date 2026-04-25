package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Accessor {
    String target();
    boolean invoker() default false;
    String desc() default "";
    boolean optional() default false;
}
