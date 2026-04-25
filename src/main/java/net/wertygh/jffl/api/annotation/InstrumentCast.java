package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(InstrumentCast.List.class)
public @interface InstrumentCast {
    String method();
    String desc() default "";
    String target() default "";
    int ordinal() default -1;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {InstrumentCast[] value();}
}
