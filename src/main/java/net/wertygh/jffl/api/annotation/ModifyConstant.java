package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(ModifyConstant.List.class)
public @interface ModifyConstant {
    String method();
    String desc() default "";
    int intValue() default -2147483648;
    long longValue() default -9223372036854775808L;
    float floatValue() default Float.NaN;
    double doubleValue() default Double.NaN;
    String stringValue() default " ";
    int ordinal() default -1;
    Slice slice() default @Slice;
    boolean cancellable() default false;
    boolean optional() default false;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {ModifyConstant[] value();}
}
