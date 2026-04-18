package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value=InsertAfter.List.class)
public @interface InsertAfter {
    String method();
    String desc() default "";
    boolean cancellable() default false;
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {InsertAfter[] value();}
}
