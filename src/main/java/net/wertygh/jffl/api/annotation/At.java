package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={})
public @interface At {
    Value value();
    String target() default "";
    int line() default -1;
    String stringValue() default " ";
    int intValue() default -2147483648;
    double doubleValue() default Double.NaN;
    int ordinal() default -1;
    Shift shift() default Shift.BEFORE;
    enum Shift {BEFORE,AFTER}
    enum Value {HEAD,RETURN,TAIL,INVOKE,FIELD,CONSTANT,LINE}
}
