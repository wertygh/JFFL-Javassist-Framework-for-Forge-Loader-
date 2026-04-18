package net.wertygh.jffl.api;

public class CallbackInfoReturnable<T> extends CallbackInfo {
    private T returnValue;
    private boolean hasReturnValue;

    public CallbackInfoReturnable(String name, boolean cancellable) {
        super(name, cancellable);
    }

    public T getReturnValue() {return returnValue;}
    public boolean hasReturnValue() {return hasReturnValue;}

    public void setReturnValue(T value) {
        this.returnValue = value;
        this.hasReturnValue = true;
        forceCancel();
    }
}
