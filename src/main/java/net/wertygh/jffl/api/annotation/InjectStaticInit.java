package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(value=InjectStaticInit.List.class)
public @interface InjectStaticInit {
    Position position() default Position.AFTER;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {InjectStaticInit[] value();}
    enum Position {BEFORE,AFTER}
}
