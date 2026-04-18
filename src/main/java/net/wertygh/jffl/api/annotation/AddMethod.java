package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value= AddMethod.List.class)
public @interface AddMethod {
    String name();
    String returnType() default "void";
    String params() default "";
    int access() default 1;
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {AddMethod[] value();}
}
