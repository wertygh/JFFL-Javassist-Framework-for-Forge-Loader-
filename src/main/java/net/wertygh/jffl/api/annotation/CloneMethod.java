package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(CloneMethod.List.class)
public @interface CloneMethod {
    String method();
    String desc() default "";
    String cloneName() default "";
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {CloneMethod[] value();}
}
