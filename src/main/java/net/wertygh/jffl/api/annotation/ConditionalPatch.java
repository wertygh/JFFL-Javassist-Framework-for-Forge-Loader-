package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.TYPE})
@Repeatable(value= ConditionalPatch.List.class)
public @interface ConditionalPatch {
    Type type();
    String value();
    String expect() default "";
    boolean negate() default false;
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.TYPE})
    @interface List {ConditionalPatch[] value();}
    enum Type {CLASS_EXISTS,SYSTEM_PROPERTY,ENV_VAR,METHOD_EXISTS}
}
