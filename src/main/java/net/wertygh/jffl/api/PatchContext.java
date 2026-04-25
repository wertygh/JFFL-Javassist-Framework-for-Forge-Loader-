package net.wertygh.jffl.api;

import javassist.ClassPool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PatchContext {
    private final String className;
    private final ClassPool classPool;
    private final Map<String, String> claims = new LinkedHashMap<>();
    private final Map<String, Integer> syntheticCounters = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public PatchContext(String className, ClassPool classPool) {
        this.className = className;
        this.classPool = classPool;
    }

    public String getClassName() {
        return className;
    }

    public ClassPool getClassPool() {
        return classPool;
    }

    public boolean claim(String key, String owner) {
        String existing = claims.putIfAbsent(key, owner);
        return existing == null || existing.equals(owner);
    }

    public String ownerOf(String key) {
        return claims.get(key);
    }

    public String nextSyntheticMethodName(String baseName) {
        int next = syntheticCounters.merge(baseName, 1, Integer::sum);
        return next == 1 ? baseName : baseName + "$" + next;
    }

    public void warn(String warning) {
        if (warning != null && !warning.isBlank()) {
            warnings.add(warning);
        }
    }

    public List<String> drainWarnings() {
        List<String> out = new ArrayList<>(warnings);
        warnings.clear();
        return out;
    }
}
