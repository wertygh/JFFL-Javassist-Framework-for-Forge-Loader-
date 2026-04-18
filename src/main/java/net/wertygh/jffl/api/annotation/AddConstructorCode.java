package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.METHOD})
@Repeatable(value= AddConstructorCode.List.class)
public @interface AddConstructorCode {
    String desc() default "";
    Position position() default Position.AFTER;
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.METHOD})
    @interface List {AddConstructorCode[] value();}
    enum Position {BEFORE,AFTER,BEFORE_SUPER}
}
