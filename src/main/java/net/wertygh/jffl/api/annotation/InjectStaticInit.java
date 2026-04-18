package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value=InjectStaticInit.List.class)
public @interface InjectStaticInit {
    Position position() default Position.AFTER;
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {InjectStaticInit[] value();}
    enum Position {BEFORE,AFTER}
}
