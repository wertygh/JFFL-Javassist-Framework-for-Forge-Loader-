package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Shadow.List.class)
public @interface Shadow {
    String target();
    boolean invoker() default false;
    String desc() default "";
    boolean optional() default false;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface List {Shadow[] value();}
}
