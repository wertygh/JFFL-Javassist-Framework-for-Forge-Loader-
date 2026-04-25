package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(WrapOperation.List.class)
public @interface WrapOperation {
    String method();
    String desc() default "";
    At at();
    Slice slice() default @Slice;
    boolean optional() default false;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {WrapOperation[] value();}
}
