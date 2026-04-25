package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ReplaceMethod.List.class)
public @interface ReplaceMethod {
    String method();
    String desc() default "";
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {ReplaceMethod[] value();}
}
