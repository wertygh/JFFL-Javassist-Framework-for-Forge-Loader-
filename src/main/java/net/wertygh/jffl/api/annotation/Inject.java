package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value=Inject.List.class)
public @interface Inject {
    String method();
    String desc() default "";
    At at();
    Slice slice() default @Slice;
    boolean cancellable() default false;
    boolean optional() default false;
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {Inject[] value();}
}
