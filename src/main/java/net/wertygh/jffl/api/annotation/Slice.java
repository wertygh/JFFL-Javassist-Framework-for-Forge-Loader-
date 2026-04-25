package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface Slice {
    At from() default @At(At.Value.HEAD);
    At to() default @At(At.Value.TAIL);
    String id() default "";
}
