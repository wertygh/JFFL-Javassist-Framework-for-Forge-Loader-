package net.wertygh.jffl.api;

public class CallbackInfo {
    private final String name;
    private final boolean cancellable;
    private boolean cancelled;

    public CallbackInfo(String name, boolean cancellable) {
        this.name = name;
        this.cancellable = cancellable;
    }

    public String getName() {return name;}
    public boolean isCancellable() {return cancellable;}
    public boolean isCancelled() {return cancelled;}

    public void cancel() {
        if (!cancellable) {throw new IllegalStateException("无法取消不可取消的回调" + name);}
        this.cancelled = true;
    }

    protected void forceCancel() {
        this.cancelled = true;
    }
}
