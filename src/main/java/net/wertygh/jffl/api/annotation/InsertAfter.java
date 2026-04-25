package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(InsertAfter.List.class)
public @interface InsertAfter {
    String method();
    String desc() default "";
    boolean cancellable() default false;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {InsertAfter[] value();}
}
