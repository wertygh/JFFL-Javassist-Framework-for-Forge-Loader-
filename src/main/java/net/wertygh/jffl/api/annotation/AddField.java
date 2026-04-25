package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(AddField.List.class)
public @interface AddField {
    String name();
    String type();
    String initializer() default "";
    int access() default 0;
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {AddField[] value();}
}
