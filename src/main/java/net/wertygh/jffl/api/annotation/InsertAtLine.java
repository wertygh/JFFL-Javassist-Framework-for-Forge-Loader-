package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(InsertAtLine.List.class)
public @interface InsertAtLine {
    String method();
    String desc() default "";
    int line();
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {InsertAtLine[] value();}
}
