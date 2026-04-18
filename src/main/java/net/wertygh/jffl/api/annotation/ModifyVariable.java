package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value=ModifyVariable.List.class)
public @interface ModifyVariable {
    String method();
    String desc() default "";
    String name() default "";
    int index() default -1;
    int line() default -1;
    At at() default @At(value=At.Value.HEAD);
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {ModifyVariable[] value();}
}
