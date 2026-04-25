package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(ConditionalPatch.List.class)
public @interface ConditionalPatch {
    Type type();
    String value();
    String expect() default "";
    boolean negate() default false;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {ConditionalPatch[] value();}
    enum Type {CLASS_EXISTS,SYSTEM_PROPERTY,ENV_VAR,METHOD_EXISTS}
}
