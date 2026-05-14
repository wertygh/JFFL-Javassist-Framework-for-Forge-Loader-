package net.wertygh.jffl.registry;

import net.wertygh.jffl.api.IClassPatch;
import net.wertygh.jffl.api.ITransformerPlugin;
import net.wertygh.jffl.api.annotation.Patch;
import net.wertygh.jffl.api.annotation.PatchDependency;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public class PatchRegistry {
    private static Logger LOGGER = LoggerFactory.getLogger(PatchRegistry.class);
    public Map<String, List<PatchEntry>> patchIndex = new HashMap<>();
    public List<PatchEntry> allPatches = new ArrayList<>();
    public List<TransformerPluginEntry> transformerPlugins = new ArrayList<>();
    public Set<String> mixinTargets = new LinkedHashSet<>();
    public List<URLClassLoader> patchClassLoaders = new ArrayList<>();
    public long nextRegistrationOrder = 0L;
    public long nextPluginRegistrationOrder = 0L;
    public static class PatchEntry implements Comparable<PatchEntry> {
        public String targetClass;
        public int priority;
        public IClassPatch patch;
        public String sourceId;
        public String patchClassName;
        public long registrationOrder;
        public List<String> dependencies;
        public List<String> optionalDependencies;

        public PatchEntry(String targetClass, int priority, IClassPatch patch, String sourceId, String patchClassName, long registrationOrder, List<String> dependencies, List<String> optionalDependencies) {
            this.targetClass = targetClass;
            this.priority = priority;
            this.patch = patch;
            this.sourceId = sourceId;
            this.patchClassName = patchClassName;
            this.registrationOrder = registrationOrder;
            this.dependencies = dependencies;
            this.optionalDependencies = optionalDependencies;
        }

        @Override
        public int compareTo(PatchEntry o) {
            int byPriority = Integer.compare(this.priority, o.priority);
            if (byPriority != 0) return byPriority;
            int bySource = this.sourceId.compareTo(o.sourceId);
            if (bySource != 0) return bySource;
            int byPatchClass = this.patchClassName.compareTo(o.patchClassName);
            if (byPatchClass != 0) return byPatchClass;
            return Long.compare(this.registrationOrder, o.registrationOrder);
        }

        public String displayName() {
            return patchClassName + " [" + sourceId + "]";
        }
    }

    public static class TransformerPluginEntry {
        public ITransformerPlugin plugin;
        public String sourceId;
        public String pluginClassName;
        public long registrationOrder;
        public Set<String> targetClasses;
        public Set<String> additionalClassPrefixes;

        public TransformerPluginEntry(ITransformerPlugin plugin, String sourceId, String pluginClassName, long registrationOrder, Set<String> targetClasses, Set<String> additionalClassPrefixes) {
            this.plugin = plugin;
            this.sourceId = sourceId;
            this.pluginClassName = pluginClassName;
            this.registrationOrder = registrationOrder;
            this.targetClasses = targetClasses;
            this.additionalClassPrefixes = additionalClassPrefixes;
        }

        public String displayName() {
            return pluginClassName + " [" + sourceId + "]";
        }
    }

    public void register(IClassPatch patch) {
        register(patch, "runtime:" + patch.getClass().getName());
    }

    public void registerPlugin(ITransformerPlugin plugin) {
        registerPlugin(plugin, "runtime:" + plugin.getClass().getName());
    }

    public void registerPlugin(ITransformerPlugin plugin, String sourceId) {
        if (plugin == null) return;
        Class<?> clazz = plugin.getClass();
        String normalizedSourceId = sourceId == null ? "unknown" : sourceId;
        Set<String> targets = new LinkedHashSet<>();
        try {
            Set<String> declaredTargets = plugin.targetClasses();
            if (declaredTargets != null) {
                for (String target : declaredTargets) {
                    if (target != null && !target.isBlank()) targets.add(target.trim().replace('/', '.'));
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("读取插件{}的targetClasses()失败", clazz.getName(), t);
        }
        Set<String> additionalClassPrefixes = collectAdditionalClassPrefixes(plugin, clazz);
        int providedPatchCount = registerPluginPatches(plugin, normalizedSourceId, clazz);
        TransformerPluginEntry entry = new TransformerPluginEntry(
                plugin, normalizedSourceId, clazz.getName(),
                nextPluginRegistrationOrder++,
                Collections.unmodifiableSet(targets),
                Collections.unmodifiableSet(additionalClassPrefixes));
        transformerPlugins.add(entry);
        LOGGER.info("注册JavassistTransformer插件{}(补丁{}个)", clazz.getSimpleName(), providedPatchCount);
    }


    private Set<String> collectAdditionalClassPrefixes(ITransformerPlugin plugin, Class<?> pluginClass) {
        LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        String[] declared;
        try {
            declared = plugin.additionalClassPrefixes();
        } catch (Throwable t) {
            LOGGER.warn("读取插件{}的additionalClassPrefixes()失败", pluginClass.getName(), t);
            return prefixes;
        }
        if (declared == null) return prefixes;
        for (int i=0;i<declared.length;i++) {
            String raw = declared[i];
            String normalized = normalizeAdditionalClassPrefix(raw);
            if (normalized == null) {
                LOGGER.warn("插件{}的additionalClassPrefixes()[{}]='{}'非法", pluginClass.getName(), i, raw);
                continue;
            }
            if (!prefixes.add(normalized)) {
                LOGGER.warn("插件{}重复声明额外类前缀{}", pluginClass.getName(), normalized);
            }
        }
        return prefixes;
    }

    private static String normalizeAdditionalClassPrefix(String prefix) {
        if (prefix == null) return null;
        String value = prefix.trim().replace('/', '.');
        if (value.isEmpty()) return null;
        if (!value.endsWith(".")) return null;
        if (value.startsWith("net.minecraft.") || value.startsWith("net.minecraftforge.")) return null;
        if (value.indexOf('.') <= 0) return null;
        String body = value.substring(0, value.length() - 1);
        if (body.isEmpty() || body.contains("..")) return null;
        String ident = "[A-Za-z_$][A-Za-z0-9_$]*";
        return body.matches(ident + "(\\." + ident + ")+") ? value : null;
    }

    private int registerPluginPatches(ITransformerPlugin plugin, String sourceId, Class<?> pluginClass) {
        String pluginPatchSourceId = sourceId + "#Plugin:" + pluginClass.getName();
        int count = 0;
        try {
            Iterable<Class<? extends IClassPatch>> patchClasses = plugin.patchClasses();
            if (patchClasses != null) {
                for (Class<? extends IClassPatch> patchClass : patchClasses) {
                    if (patchClass == null) continue;
                    try {
                        IClassPatch patch = patchClass.getDeclaredConstructor().newInstance();
                        register(patch, pluginPatchSourceId);
                        count++;
                    } catch (Throwable t) {
                        LOGGER.error("插件{}提供的补丁类{}实例化失败", pluginClass.getName(), patchClass.getName(), t);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("读取插件{}的patchClasses()失败", pluginClass.getName(), t);
        }
        try {
            Iterable<? extends IClassPatch> patches = plugin.patches();
            if (patches != null) {
                for (IClassPatch patch : patches) {
                    if (patch == null) continue;
                    register(patch, pluginPatchSourceId);
                    count++;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("读取插件{}的patches()失败", pluginClass.getName(), t);
        }
        return count;
    }

    public void register(IClassPatch patch, String sourceId) {
        Class<?> clazz = patch.getClass();
        Patch[] anns = clazz.getAnnotationsByType(Patch.class);
        if (anns.length == 0) {
            LOGGER.error("补丁类{}没有@Patch注解, 无法注册", clazz.getName());
            return;
        }
        for (Patch ann : anns) {
            String target = ann.value();
            if (target == null || target.isEmpty()) {
                LOGGER.warn("补丁{}的@Patch.value()为空", clazz.getName());
                continue;
            }
            PatchEntry entry = new PatchEntry(
                target, ann.priority(), patch,
                sourceId == null ? "unknown" : sourceId,
                clazz.getName(), nextRegistrationOrder++,
                collectRequiredDependencies(clazz, ann),
                collectOptionalDependencies(clazz)
            );
            allPatches.add(entry);
            List<PatchEntry> list = patchIndex.computeIfAbsent(target, k -> new ArrayList<>());
            list.add(entry);
            list.sort(Comparator.naturalOrder());
            LOGGER.info("为目标{}注册了补丁{}(优先级{}, 来源={})",
                    target, clazz.getSimpleName(), ann.priority(), entry.sourceId);
        }
    }

    private static List<String> collectRequiredDependencies(Class<?> clazz, Patch patch) {
        LinkedHashSet<String> deps = new LinkedHashSet<>();
        for (String dep : patch.dependsOn()) {
            if (dep != null && !dep.isBlank()) deps.add(dep.trim());
        }
        for (PatchDependency dep : clazz.getAnnotationsByType(PatchDependency.class)) {
            if (!dep.optional() && dep.value() != null && !dep.value().isBlank()) deps.add(dep.value().trim());
        }
        return List.copyOf(deps);
    }

    private static List<String> collectOptionalDependencies(Class<?> clazz) {
        LinkedHashSet<String> deps = new LinkedHashSet<>();
        for (PatchDependency dep : clazz.getAnnotationsByType(PatchDependency.class)) {
            if (dep.optional() && dep.value() != null && !dep.value().isBlank()) deps.add(dep.value().trim());
        }
        return List.copyOf(deps);
    }

    public void detectMixinTargets(File modsDir) {
        if (!modsDir.exists() || !modsDir.isDirectory()) return;
        File[] jars = modsDir.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null) return;
        for (File jar : jars) {
            try (JarFile jf = new JarFile(jar)) {
                Set<String> mixinClassNames = new LinkedHashSet<>();
                for (var entries = jf.entries(); entries.hasMoreElements(); ) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.contains("/")) continue;
                    if (name.endsWith("refmap.json") || name.contains("refmap")) continue;
                    if (!name.endsWith(".json")) continue;
                    if (name.endsWith(".mixins.json") || name.contains("mixin")) {
                        try (var is = jf.getInputStream(entry);
                             var scanner = new Scanner(is)) {
                            String content = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
                            if (content.contains("\"package\"")) {
                                collectMixinClassNames(content, mixinClassNames);
                            }
                        } catch (Exception e) {
                            LOGGER.debug("解析Mixin配置{}失败：{}", name, e.getMessage());
                        }
                    }
                }
                for (String mixinClassName : mixinClassNames) {
                    String classPath = mixinClassName.replace('.', '/') + ".class";
                    JarEntry classEntry = jf.getJarEntry(classPath);
                    if (classEntry == null) continue;
                    try (var is = jf.getInputStream(classEntry)) {
                        extractMixinTargetsFromClass(is.readAllBytes());
                    } catch (Exception e) {
                        LOGGER.debug("无法从{}读取Mixin类{}：{}",
                                mixinClassName, jar.getName(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("获取Mixin配置失败：{}", jar.getName());
            }
        }
    }

    private void collectMixinClassNames(String json, Set<String> out) {
        String pkg = extractJsonString(json, "package");
        if (pkg == null) return;
        for (String arrayKey : new String[]{"mixins", "client", "server"}) {
            List<String> classes = extractJsonStringArray(json, arrayKey);
            for (String cls : classes) {
                out.add(pkg + "." + cls);
            }
        }
    }

    private void extractMixinTargetsFromClass(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(descriptor)) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitArray(String name) {
                            if ("value".equals(name)) {
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String n, Object value) {
                                        if (value instanceof Type t) {
                                            mixinTargets.add(t.getClassName());
                                        }
                                    }
                                };
                            } else if ("targets".equals(name)) {
                                return new AnnotationVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visit(String n, Object value) {
                                        if (value instanceof String s && !s.isEmpty()) {
                                            mixinTargets.add(s.replace('/', '.'));
                                        }
                                    }
                                };
                            }
                            return super.visitArray(name);
                        }

                        @Override
                        public void visit(String name, Object value) {
                            if ("value".equals(name) && value instanceof Type t) {
                                mixinTargets.add(t.getClassName());
                            } else if ("targets".equals(name) && value instanceof String s && !s.isEmpty()) {
                                mixinTargets.add(s.replace('/', '.'));
                            }
                        }
                    };
                }
                return super.visitAnnotation(descriptor, visible);
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static List<String> extractJsonStringArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return result;
        int bracketStart = json.indexOf('[', idx + pattern.length());
        if (bracketStart < 0) return result;
        int bracketEnd = findMatchingBracket(json, bracketStart);
        if (bracketEnd < 0) return result;
        String arrayContent = json.substring(bracketStart + 1, bracketEnd);
        for (String part : arrayContent.split(",")) {
            String trimmed = part.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                result.add(trimmed.substring(1, trimmed.length() - 1));
            }
        }
        return result;
    }

    private static int findMatchingBracket(String json, int openPos) {
        int depth = 0;
        boolean inString = false;
        for (int i=openPos;i<json.length();i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) continue;
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    public void scanDirectory(File modsDir) {
        if (!modsDir.exists() || !modsDir.isDirectory()) {
            LOGGER.warn("未找到补丁目录：{}", modsDir);
            return;
        }
        detectMixinTargets(modsDir);
        File[] jars = modsDir.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null || jars.length == 0) return;
        URLClassLoader loader = buildPatchClassLoader(List.of(jars));
        patchClassLoaders.add(loader);
        for (File jar : jars) {
            scanJar(jar, loader);
        }
    }

    private void scanJar(File jarFile) {
        URLClassLoader loader = buildPatchClassLoader(List.of(jarFile));
        patchClassLoaders.add(loader);
        scanJar(jarFile, loader);
    }

    private void scanJar(File jarFile, ClassLoader loader) {
        try (JarFile jar = new JarFile(jarFile)) {
            String patchList = null;
            if (jar.getManifest() != null) {
                patchList = jar.getManifest().getMainAttributes().getValue("JFFL-Patches");
            }
            JarEntry serviceEntry = jar.getJarEntry("META-INF/JFFL/IClassPatch.txt");
            if (patchList == null && serviceEntry == null) return;
            Set<String> classNames = new LinkedHashSet<>();
            Set<String> pluginClassNames = new LinkedHashSet<>();
                if (patchList != null) {
                    for (String name : patchList.split(",")) {
                        String trimmed = name.trim();
                        if (!trimmed.isEmpty()) classNames.add(trimmed);
                    }
                }
                if (serviceEntry != null) {
                    try (var is = jar.getInputStream(serviceEntry);
                         var scanner = new Scanner(is)) {
                        String currentPrefix = null;
                        while (scanner.hasNextLine()) {
                            String line = scanner.nextLine().trim();
                            if (line.isEmpty() || line.startsWith("#")) continue;
                            if (line.startsWith("Plugin:")) {
                                String pluginName = resolveServiceClassName(line.substring("Plugin:".length()).trim(), currentPrefix);
                                if (isFullyQualifiedClassName(pluginName)) {
                                    pluginClassNames.add(pluginName);
                                } else {
                                    LOGGER.warn("无效插件声明'{}', \"Plugin:\"后必须是全限定名", line);
                                }
                                continue;
                            }
                            if (line.endsWith("#")) {
                                currentPrefix = line.substring(0, line.length() - 1);
                                if (!currentPrefix.endsWith(".")) currentPrefix += ".";
                                continue;
                            }
                            if (line.endsWith(".*")) {
                                String pkg = line.substring(0, line.length() - 2);
                                Set<String> found = scanPackageInJar(jar, pkg);
                                classNames.addAll(found);
                                continue;
                            }
                            if (currentPrefix != null && !line.contains(".")) {
                                classNames.add(currentPrefix + line);
                            } else {
                                classNames.add(line);
                            }
                        }
                    }
                }
            for (String className : classNames) {
                loadPatchClass(loader, className, jarFile.getAbsolutePath(), jarFile.getName(), true);
            }
            for (String pluginClassName : pluginClassNames) {
                loadPluginClass(loader, pluginClassName, jarFile.getAbsolutePath(), jarFile.getName());
            }
        } catch (Exception e) {
            LOGGER.error("获取补丁类失败：{}", jarFile, e);
        }
    }

    private Set<String> scanPackageInJar(JarFile jar, String packageName) {
        String pkgPath = packageName.replace('.', '/') + "/";
        Set<String> result = new LinkedHashSet<>();
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith(pkgPath) && name.endsWith(".class") && !entry.isDirectory()) {
                String remainder = name.substring(pkgPath.length());
                if (!remainder.contains("/")) {
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    result.add(className);
                }
            }
        }
        return result;
    }

    public boolean hasPatches(String className) {
        return patchIndex.containsKey(className);
    }

    public Set<String> getTargetClasses() {
        return Collections.unmodifiableSet(patchIndex.keySet());
    }

    public List<PatchEntry> getPatches(String className) {
        List<PatchEntry> list = patchIndex.getOrDefault(className, Collections.emptyList());
        if (list.isEmpty()) return list;
        return Collections.unmodifiableList(resolveDependencies(className, list));
    }

    private List<PatchEntry> resolveDependencies(String targetClass, List<PatchEntry> source) {
        List<PatchEntry> candidates = new ArrayList<>();
        for (PatchEntry entry : source) {
            if (requiredDependenciesPresent(entry)) {
                candidates.add(entry);
            } else {
                LOGGER.warn("补丁{}缺少必需依赖{}", entry.displayName(), entry.dependencies);
            }
        }
        candidates.sort(Comparator.naturalOrder());
        List<PatchEntry> ordered = new ArrayList<>(candidates.size());
        Map<PatchEntry, VisitState> state = new HashMap<>();
        for (PatchEntry entry : candidates) {
            visitForDependencyOrder(targetClass, entry, candidates, state, ordered, new ArrayDeque<>());
        }
        return ordered;
    }

    private enum VisitState {VISITING,VISITED}

    private boolean visitForDependencyOrder(String targetClass, PatchEntry entry, List<PatchEntry> candidates, Map<PatchEntry, VisitState> state, List<PatchEntry> ordered, ArrayDeque<PatchEntry> stack) {
        VisitState current = state.get(entry);
        if (current == VisitState.VISITED) return true;
        if (current == VisitState.VISITING) {
            LOGGER.warn("检测到{}上的补丁依赖循环: {}", targetClass, describeCycle(stack, entry));
            return false;
        }
        state.put(entry, VisitState.VISITING);
        stack.push(entry);
        for (String dep : entry.dependencies) {
            PatchEntry dependency = findMatchingEntry(candidates, dep);
            if (dependency != null) {
                visitForDependencyOrder(targetClass, dependency, candidates, state, ordered, stack);
            }
        }
        for (String dep : entry.optionalDependencies) {
            PatchEntry dependency = findMatchingEntry(candidates, dep);
            if (dependency != null) {
                visitForDependencyOrder(targetClass, dependency, candidates, state, ordered, stack);
            }
        }
        stack.pop();
        state.put(entry, VisitState.VISITED);
        if (!ordered.contains(entry)) ordered.add(entry);
        return true;
    }

    private boolean requiredDependenciesPresent(PatchEntry entry) {
        for (String dep : entry.dependencies) {
            if (findMatchingEntry(allPatches, dep) == null) return false;
        }
        return true;
    }

    private static PatchEntry findMatchingEntry(List<PatchEntry> entries, String dependency) {
        if (dependency == null || dependency.isBlank()) return null;
        for (PatchEntry candidate : entries) {
            if (matchesDependency(candidate, dependency.trim())) return candidate;
        }
        return null;
    }

    private static boolean matchesDependency(PatchEntry entry, String dependency) {
        if (dependency.equals(entry.patchClassName)) return true;
        int lastDot = entry.patchClassName.lastIndexOf('.');
        String simpleName = lastDot >= 0 ? entry.patchClassName.substring(lastDot + 1) : entry.patchClassName;
        if (dependency.equals(simpleName)) return true;
        if (dependency.equals(entry.sourceId)) return true;
        if (dependency.equals(entry.sourceId + ":" + entry.patchClassName)) return true;
        if (dependency.equals(entry.sourceId + "#" + entry.patchClassName)) return true;
        if (dependency.equals(entry.sourceId + ":" + simpleName)) return true;
        if (dependency.equals(entry.sourceId + "#" + simpleName)) return true;
        return false;
    }

    private static String describeCycle(ArrayDeque<PatchEntry> stack, PatchEntry repeated) {
        StringBuilder sb = new StringBuilder(repeated.patchClassName);
        for (PatchEntry entry : stack) {
            sb.append(" <- ").append(entry.patchClassName);
        }
        return sb.toString();
    }

    public Set<String> getMixinTargets() {
        return Collections.unmodifiableSet(mixinTargets);
    }

    public List<TransformerPluginEntry> getTransformerPlugins() {
        return Collections.unmodifiableList(transformerPlugins);
    }

    public Set<String> getAdditionalClassPrefixes() {
        LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        for (TransformerPluginEntry entry : transformerPlugins) {
            prefixes.addAll(entry.additionalClassPrefixes);
        }
        return Collections.unmodifiableSet(prefixes);
    }

    public boolean hasTransformerPlugins() {
        return !transformerPlugins.isEmpty();
    }

    public Set<String> getTransformerTargetClasses() {
        LinkedHashSet<String> targets = new LinkedHashSet<>(patchIndex.keySet());
        for (TransformerPluginEntry entry : transformerPlugins) {
            targets.addAll(entry.targetClasses);
        }
        return Collections.unmodifiableSet(targets);
    }

    public void scanClassPath(String classpath) {
        if (classpath == null || classpath.isBlank()) return;
        List<File> entries = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            File f = new File(entry);
            if (f.exists() && (f.isDirectory() || entry.endsWith(".jar"))) entries.add(f);
        }
        if (entries.isEmpty()) return;
        URLClassLoader loader = buildPatchClassLoader(entries);
        patchClassLoaders.add(loader);
        for (File f : entries) {
            if (f.isDirectory()) {
                scanDirectoryEntry(f, loader);
            } else if (f.getName().endsWith(".jar")) {
                scanJar(f, loader);
            }
        }
    }

    public void scanModClasses(String modClasses) {
        if (modClasses == null || modClasses.isBlank()) return;
        Set<String> seen = new LinkedHashSet<>();
        List<File> entries = new ArrayList<>();
        for (String entry : modClasses.split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            int sep = entry.indexOf("%%");
            String path = sep >= 0 ? entry.substring(sep + 2) : entry;
            if (!path.isBlank() && seen.add(path)) {
                File f = new File(path);
                if (f.exists() && (f.isDirectory() || (f.isFile() && path.endsWith(".jar")))) entries.add(f);
            }
        }
        if (entries.isEmpty()) return;
        URLClassLoader loader = buildPatchClassLoader(entries);
        patchClassLoaders.add(loader);
        for (File f : entries) {
            if (f.isDirectory()) {
                scanDirectoryEntry(f, loader);
            } else if (f.isFile() && f.getName().endsWith(".jar")) {
                scanJar(f, loader);
            }
        }
    }

    private void scanDirectoryEntry(File dir) {
        URLClassLoader loader = buildPatchClassLoader(List.of(dir));
        patchClassLoaders.add(loader);
        scanDirectoryEntry(dir, loader);
    }

    private void scanDirectoryEntry(File dir, ClassLoader loader) {
        File serviceFile = new File(dir, "META-INF/JFFL/IClassPatch.txt");
        File manifestFile = new File(dir, "META-INF/MANIFEST.MF");
        if (!serviceFile.isFile() && !manifestFile.isFile()) return;
        Set<String> classNames = new LinkedHashSet<>();
        Set<String> pluginClassNames = new LinkedHashSet<>();
        if (manifestFile.isFile()) {
            try (var is = Files.newInputStream(manifestFile.toPath())) {
                java.util.jar.Manifest mf = new java.util.jar.Manifest(is);
                String list = mf.getMainAttributes().getValue("JFFL-Patches");
                if (list != null) {
                    for (String n : list.split(",")) {
                        String t = n.trim();
                        if (!t.isEmpty()) classNames.add(t);
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("读取目录{}的MANIFEST.MF失败", dir, e);
            }
        }
        if (serviceFile.isFile()) {
            try (var scanner = new Scanner(serviceFile)) {
                String currentPrefix = null;
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("Plugin:")) {
                        String pluginName = resolveServiceClassName(line.substring("Plugin:".length()).trim(), currentPrefix);
                        if (isFullyQualifiedClassName(pluginName)) {
                            pluginClassNames.add(pluginName);
                        } else {
                            LOGGER.warn("无效插件声明'{}', \"Plugin:\"后必须是全限定名", line);
                        }
                        continue;
                    }
                    if (line.endsWith("#")) {
                        currentPrefix = line.substring(0, line.length() - 1);
                        if (!currentPrefix.endsWith(".")) currentPrefix += ".";
                        continue;
                    }
                    if (line.endsWith(".*")) {
                        String pkg = line.substring(0, line.length() - 2);
                        classNames.addAll(scanPackageInDir(dir, pkg));
                        continue;
                    }
                    if (currentPrefix != null && !line.contains(".")) {
                        classNames.add(currentPrefix + line);
                    } else {
                        classNames.add(line);
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("读取目录{}的服务文件失败", dir, e);
            }
        }
        if (classNames.isEmpty() && pluginClassNames.isEmpty()) return;
        for (String className : classNames) {
            loadPatchClass(loader, className, dir.getAbsolutePath(), dir.toString(), false);
        }
        for (String pluginClassName : pluginClassNames) {
            loadPluginClass(loader, pluginClassName, dir.getAbsolutePath(), dir.toString());
        }
    }

    private void loadPatchClass(ClassLoader loader, String className, String sourceId, String sourceName, boolean errorOnMissing) {
        try {
            Class<?> patchClass = loader.loadClass(className);
            if (!IClassPatch.class.isAssignableFrom(patchClass)) {
                LOGGER.warn("类'{}'未实现IClassPatch, 来自: {}", className, sourceName);
                return;
            }
            if (patchClass.getAnnotationsByType(Patch.class).length == 0) {
                LOGGER.warn("类'{}'缺少@Patch注解, 来自: {}", className, sourceName);
                return;
            }
            IClassPatch patch = (IClassPatch) patchClass.getDeclaredConstructor().newInstance();
            register(patch, sourceId);
        } catch (ClassNotFoundException e) {
            if (errorOnMissing) LOGGER.error("在{}中未找到补丁类'{}'", sourceName, className);
            else LOGGER.debug("在{}中未找到声明的补丁类'{}'", sourceName, className);
        } catch (Exception e) {
            LOGGER.error("从{}加载补丁类{}失败", sourceName, className, e);
        }
    }

    private void loadPluginClass(ClassLoader loader, String className, String sourceId, String sourceName) {
        try {
            Class<?> pluginClass = loader.loadClass(className);
            if (!ITransformerPlugin.class.isAssignableFrom(pluginClass)) {
                LOGGER.warn("Plugin声明的类'{}'未实现ITransformerPlugin, 来自: {}", className, sourceName);
                return;
            }
            ITransformerPlugin plugin = (ITransformerPlugin) pluginClass.getDeclaredConstructor().newInstance();
            registerPlugin(plugin, sourceId);
        } catch (ClassNotFoundException e) {
            LOGGER.error("在{}中未找到插件类'{}'", sourceName, className);
        } catch (Exception e) {
            LOGGER.error("从{}加载插件类{}失败", sourceName, className, e);
        }
    }

    private static String resolveServiceClassName(String name, String currentPrefix) {
        if (name == null) return "";
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return trimmed;
        if (currentPrefix != null && !trimmed.contains(".")) return currentPrefix + trimmed;
        return trimmed;
    }

    private static boolean isFullyQualifiedClassName(String className) {
        if (className == null || className.isBlank() || !className.contains(".")) return false;
        String ident = "[A-Za-z_$][A-Za-z0-9_$]*";
        return className.matches(ident + "(\\." + ident + ")+" );
    }

    private URLClassLoader buildPatchClassLoader(List<File> entries) {
        try {
            URL[] urls = new URL[entries.size()];
            for (int i=0;i<entries.size();i++) {
                urls[i] = entries.get(i).toURI().toURL();
            }
            return new URLClassLoader(urls, PatchRegistry.class.getClassLoader());
        } catch (Exception e) {
            LOGGER.error("无法构建补丁URLClassLoader", e);
            return new URLClassLoader(new URL[0], PatchRegistry.class.getClassLoader());
        }
    }

    private Set<String> scanPackageInDir(File root, String packageName) {
        Path pkgDir = root.toPath().resolve(packageName.replace('.', '/'));
        if (!Files.isDirectory(pkgDir)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        try (Stream<Path> s = Files.list(pkgDir)) {
            s.filter(p -> p.toString().endsWith(".class") && Files.isRegularFile(p))
             .forEach(p -> {
                 String fn = p.getFileName().toString();
                 String simple = fn.substring(0, fn.length() - 6);
                 if (!simple.contains("$")) out.add(packageName + "." + simple);
             });
        } catch (Exception e) {
            LOGGER.debug("扫描目录包{}失败", pkgDir, e);
        }
        return out;
    }
}
