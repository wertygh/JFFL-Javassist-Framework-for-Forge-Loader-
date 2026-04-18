package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value=WrapTryCatch.List.class)
public @interface WrapTryCatch {
    String method();
    String desc() default "";
    String exceptionType() default "java.lang.Throwable";
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {WrapTryCatch[] value();}
}
