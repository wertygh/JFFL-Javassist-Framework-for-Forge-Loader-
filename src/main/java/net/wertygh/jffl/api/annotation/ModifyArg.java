package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ModifyArg.List.class)
public @interface ModifyArg {
    String method();
    String desc() default "";
    String target();
    int index();
    int ordinal() default -1;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {ModifyArg[] value();}
}
