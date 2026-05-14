package net.wertygh.jffl.api;

import java.net.URL;
import java.util.List;
import java.util.Optional;
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

    default String[] additionalClassPrefixes() {
        return new String[0];
    }

    default Optional<URL> locateAdditionalClass(IAdditionalClassHook hook) throws Exception {
        if (hook == null) return Optional.empty();
        ClassLoader loader = getClass().getClassLoader();
        URL url;
        if (loader != null) {
            url = loader.getResource(hook.getResourceName());
        } else {
            url = ClassLoader.getSystemResource(hook.getResourceName());
        }
        return Optional.ofNullable(url);
    }

    default byte[] toByte(ITransformerHook hook) throws Exception {
        return hook.getBytes();
    }
}
