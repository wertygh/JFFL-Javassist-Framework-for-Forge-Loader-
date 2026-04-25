package net.wertygh.jffl.api.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(AddInterface.List.class)
public @interface AddInterface {
    String value();
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {AddInterface[] value();}
}
