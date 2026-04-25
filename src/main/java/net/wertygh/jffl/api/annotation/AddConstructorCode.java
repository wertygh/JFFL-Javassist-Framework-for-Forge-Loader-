package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(AddConstructorCode.List.class)
public @interface AddConstructorCode {
    String desc() default "";
    Position position() default Position.AFTER;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {AddConstructorCode[] value();}
    enum Position {BEFORE,AFTER,BEFORE_SUPER}
}
