package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(value=RetentionPolicy.RUNTIME)
@Target(value={ElementType.TYPE})
@Repeatable(value= AddField.List.class)
public @interface AddField {
    String name();
    String type();
    String initializer() default "";
    int access() default 0;
    @Retention(value=RetentionPolicy.RUNTIME)
    @Target(value={ElementType.TYPE})
    @interface List {AddField[] value();}
}
