package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(InstrumentFieldAccess.List.class)
public @interface InstrumentFieldAccess {
    String method();
    String desc() default "";
    String target() default "";
    AccessType accessType() default AccessType.BOTH;
    int ordinal() default -1;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {InstrumentFieldAccess[] value();}
    enum AccessType {READ,WRITE,BOTH}
}
