package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value=ReplaceMethod.List.class)
public @interface ReplaceMethod {
    String method();
    String desc() default "";
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {ReplaceMethod[] value();}
}
