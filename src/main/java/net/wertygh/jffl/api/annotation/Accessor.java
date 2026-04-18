package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
public @interface Accessor {
    String target();
    boolean invoker() default false;
    String desc() default "";
    boolean optional() default false;
}
