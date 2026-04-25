package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(InstrumentInstanceof.List.class)
public @interface InstrumentInstanceof {
    String method();
    String desc() default "";
    String target() default "";
    int ordinal() default -1;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {InstrumentInstanceof[] value();}
}
