package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value=InstrumentInstanceof.List.class)
public @interface InstrumentInstanceof {
    String method();
    String desc() default "";
    String target() default "";
    int ordinal() default -1;
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {InstrumentInstanceof[] value();}
}
