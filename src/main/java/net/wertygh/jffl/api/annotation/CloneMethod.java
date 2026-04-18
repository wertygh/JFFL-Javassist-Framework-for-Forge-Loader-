package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value= CloneMethod.List.class)
public @interface CloneMethod {
    String method();
    String desc() default "";
    String cloneName() default "";
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {CloneMethod[] value();}
}
