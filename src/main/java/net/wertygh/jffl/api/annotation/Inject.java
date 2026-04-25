package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Inject.List.class)
public @interface Inject {
    String method();
    String desc() default "";
    At at();
    Slice slice() default @Slice;
    boolean cancellable() default false;
    boolean optional() default false;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {Inject[] value();}
}
