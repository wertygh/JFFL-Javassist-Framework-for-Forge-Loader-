package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value=WrapMethod.List.class)
public @interface WrapMethod {
    String method();
    String desc() default "";
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {WrapMethod[] value();}
}
