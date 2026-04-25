package net.wertygh.jffl.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(Patch.List.class)
public @interface Patch {
    String value();
    int priority() default 1000;
    boolean optional() default false;
    String[] dependsOn() default {};
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {Patch[] value();}
}
