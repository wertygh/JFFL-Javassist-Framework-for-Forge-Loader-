package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={})
public @interface Slice {
    At from() default @At(value=At.Value.HEAD);
    At to() default @At(value=At.Value.TAIL);
    String id() default "";
}
