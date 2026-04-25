package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(AddMethod.List.class)
public @interface AddMethod {
    String name();
    String returnType() default "void";
    String params() default "";
    int access() default 1;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {AddMethod[] value();}
}
