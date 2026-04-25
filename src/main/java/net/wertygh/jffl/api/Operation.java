package net.wertygh.jffl.api;

@FunctionalInterface
public interface Operation<T> {
    T call(Object... args) throws Throwable;
}
