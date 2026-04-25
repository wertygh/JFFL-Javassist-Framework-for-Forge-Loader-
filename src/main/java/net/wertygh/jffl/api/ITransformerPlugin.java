package net.wertygh.jffl.api;

import java.util.List;
import java.util.Set;

public interface ITransformerPlugin {
    default Set<String> targetClasses() {
        return Set.of();
    }
    default Iterable<Class<? extends IClassPatch>> patchClasses() {
        return List.of();
    }
    default Iterable<? extends IClassPatch> patches() {
        return List.of();
    }
    default byte[] toByte(ITransformerHook hook) throws Exception {
        return hook.getBytes();
    }
}
