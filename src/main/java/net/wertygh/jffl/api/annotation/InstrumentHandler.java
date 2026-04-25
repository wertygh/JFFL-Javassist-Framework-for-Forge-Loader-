package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(InstrumentHandler.List.class)
public @interface InstrumentHandler {
    String method();
    String desc() default "";
    String exceptionType() default "";
    int ordinal() default -1;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {InstrumentHandler[] value();}
}
