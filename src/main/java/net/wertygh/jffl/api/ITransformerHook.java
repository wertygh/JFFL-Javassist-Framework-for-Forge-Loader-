package net.wertygh.jffl.api;

public interface ITransformerHook {
    String getClassName();
    String getInternalName();
    byte[] getBytes();
    byte[] getOriginalBytes();
    boolean isPatched();
}
