package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(WrapTryCatch.List.class)
public @interface WrapTryCatch {
    String method();
    String desc() default "";
    String exceptionType() default "java.lang.Throwable";
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {WrapTryCatch[] value();}
}
