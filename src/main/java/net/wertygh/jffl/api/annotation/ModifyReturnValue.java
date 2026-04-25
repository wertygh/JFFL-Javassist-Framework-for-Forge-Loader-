package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ModifyReturnValue.List.class)
public @interface ModifyReturnValue {
    String method();
    String desc() default "";
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {ModifyReturnValue[] value();}
}
