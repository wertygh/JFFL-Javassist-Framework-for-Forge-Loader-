package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(InsertBefore.List.class)
public @interface InsertBefore {
    String method();
    String desc() default "";
    boolean cancellable() default false;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {InsertBefore[] value();}
}
