package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value=InstrumentFieldAccess.List.class)
public @interface InstrumentFieldAccess {
    String method();
    String desc() default "";
    String target() default "";
    AccessType accessType() default AccessType.BOTH;
    int ordinal() default -1;
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {InstrumentFieldAccess[] value();}
    enum AccessType {READ,WRITE,BOTH}
}
