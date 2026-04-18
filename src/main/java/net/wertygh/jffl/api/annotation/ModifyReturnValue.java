package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value=ModifyReturnValue.List.class)
public @interface ModifyReturnValue {
    String method();
    String desc() default "";
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {ModifyReturnValue[] value();}
}
