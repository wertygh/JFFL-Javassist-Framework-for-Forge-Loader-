package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value=ModifyArg.List.class)
public @interface ModifyArg {
    String method();
    String desc() default "";
    String target();
    int index();
    int ordinal() default -1;
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {ModifyArg[] value();}
}
