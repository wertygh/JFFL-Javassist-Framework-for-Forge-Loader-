package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value=InstrumentHandler.List.class)
public @interface InstrumentHandler {
    String method();
    String desc() default "";
    String exceptionType() default "";
    int ordinal() default -1;
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {InstrumentHandler[] value();}
}
