package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ModifyVariable.List.class)
public @interface ModifyVariable {
    String method();
    String desc() default "";
    String name() default "";
    int index() default -1;
    int line() default -1;
    At at() default @At(value=At.Value.HEAD);
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {ModifyVariable[] value();}
}
