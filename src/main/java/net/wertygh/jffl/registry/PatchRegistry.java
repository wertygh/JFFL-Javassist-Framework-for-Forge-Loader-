package net.wertygh.jffl.registry;

import net.wertygh.jffl.api.IClassPatch;
import net.wertygh.jffl.api.annotation.Patch;
import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PatchRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(PatchRegistry.class);
    private final Map<String, List<PatchEntry>> patchIndex = new HashMap<>();
    private final List<PatchEntry> allPatches = new ArrayList<>();
    private final Set<String> mixinTargets = new LinkedHashSet<>();
    private final List<URLClassLoader> patchClassLoaders = new ArrayList<>();

    public record PatchEntry(String targetClass, int priority, IClassPatch patch) implements Comparable<PatchEntry> {
        @Override
        public int compareTo(PatchEntry o) {
            return Integer.compare(this.priority, o.priority);
        }
    }

    public void register(IClassPatch patch) {
        Class<?> clazz = patch.getClass();
        Patch ann = clazz.getAnnotation(Patch.class);
        String target = ann.value();
        PatchEntry entry = new PatchEntry(target, ann.priority(), patch);
        allPatches.add(entry);
        patchIndex.computeIfAbsent(target, k -> new ArrayList<>()).add(entry);
        patchIndex.get(target).sort(Comparator.naturalOrder());
        LOGGER.info("为目标{}注册了补丁{}(优先级{})", clazz.getSimpleName(), target, ann.priority());
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
        for (int i = openPos; i < json.length(); i++) {
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
        if (jars == null) return;
        for (File jar : jars) {
            scanJar(jar);
        }
    }

    private void scanJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            String patchList = null;
            if (jar.getManifest() != null) {
                patchList = jar.getManifest().getMainAttributes().getValue("JFFL-Patches");
            }
            JarEntry serviceEntry = jar.getJarEntry("META-INF/services/net.wertygh.jffl.api.IClassPatch");
            if (patchList == null && serviceEntry == null) return;
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{jarFile.toURI().toURL()},
                    PatchRegistry.class.getClassLoader()
            );
            try {
                Set<String> classNames = new LinkedHashSet<>();
                if (patchList != null) {
                    for (String name : patchList.split(",")) {
                        String trimmed = name.trim();
                        if (!trimmed.isEmpty()) classNames.add(trimmed);
                    }
                }
                if (serviceEntry != null) {
                    try (var is = jar.getInputStream(serviceEntry);
                         var scanner = new java.util.Scanner(is)) {
                        String currentPrefix = null;
                        while (scanner.hasNextLine()) {
                            String line = scanner.nextLine().trim();
                            if (line.isEmpty() || line.startsWith("#")) continue;
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
                    try {
                        Class<?> patchClass = loader.loadClass(className);
                        if (!IClassPatch.class.isAssignableFrom(patchClass)) {
                            LOGGER.warn("在服务文件中声明的类'{}'未实现IClassPatch",className);
                            continue;
                        }
                        if (!patchClass.isAnnotationPresent(Patch.class)) {
                            LOGGER.warn("服务文件中声明的类'{}'缺少@Patch注解", className);
                            continue;
                        }
                        IClassPatch patch = (IClassPatch) patchClass.getDeclaredConstructor().newInstance();
                        register(patch);
                    } catch (ClassNotFoundException e) {
                        LOGGER.error("在{}中未找到补丁类'{}'", className, jarFile.getName());
                    } catch (Exception e) {
                        LOGGER.error("无法从{}加载补丁类{}",className, jarFile.getName(), e);
                    }
                }
            } finally {
                patchClassLoaders.add(loader);
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
                    String className = name.substring(0,name.length()-6).replace('/','.');
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
        return patchIndex.getOrDefault(className, Collections.emptyList());
    }

    public Set<String> getMixinTargets() {
        return Collections.unmodifiableSet(mixinTargets);
    }

    public void scanClassPath(String classpath) {
        if (classpath == null || classpath.isBlank()) return;
        for (String entry : classpath.split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            File f = new File(entry);
            if (!f.exists()) continue;
            if (f.isDirectory()) {
                scanDirectoryEntry(f);
            } else if (entry.endsWith(".jar")) {
                scanJar(f);
            }
        }
    }
    
    public void scanModClasses(String modClasses) {
        if (modClasses == null || modClasses.isBlank()) return;
        Set<String> seen = new LinkedHashSet<>();
        for (String entry : modClasses.split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            int sep = entry.indexOf("%%");
            String path = sep >= 0 ? entry.substring(sep + 2) : entry;
            if (!path.isBlank() && seen.add(path)) {
                File f = new File(path);
                if (f.isDirectory()) {
                    scanDirectoryEntry(f);
                } else if (f.isFile() && path.endsWith(".jar")) {
                    scanJar(f);
                }
            }
        }
    }
    
    private void scanDirectoryEntry(File dir) {
        File serviceFile = new File(dir, "META-INF/services/net.wertygh.jffl.api.IClassPatch");
        File manifestFile = new File(dir, "META-INF/MANIFEST.MF");
        if (!serviceFile.isFile() && !manifestFile.isFile()) return;
        Set<String> classNames = new LinkedHashSet<>();
        if (manifestFile.isFile()) {
            try (var is = java.nio.file.Files.newInputStream(manifestFile.toPath())) {
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
        if (classNames.isEmpty()) return;
        URLClassLoader loader;
        try {
            loader = new URLClassLoader(new URL[]{dir.toURI().toURL()}, PatchRegistry.class.getClassLoader());
        } catch (Exception e) {
            LOGGER.error("无法为补丁目录{}构建URLClassLoader", dir, e);
            return;
        }
        for (String className : classNames) {
            try {
                Class<?> patchClass = loader.loadClass(className);
                if (!IClassPatch.class.isAssignableFrom(patchClass)) {
                    LOGGER.warn("类'{}'未实现IClassPatch, 目录: {}", className, dir);
                    continue;
                }
                if (!patchClass.isAnnotationPresent(Patch.class)) {
                    LOGGER.warn("类'{}'缺少@Patch注解, 目录: {}", className, dir);
                    continue;
                }
                IClassPatch patch = (IClassPatch) patchClass.getDeclaredConstructor().newInstance();
                register(patch);
            } catch (ClassNotFoundException e) {
                LOGGER.debug("在目录{}中未找到声明的补丁类'{}'", dir, className);
            } catch (Exception e) {
                LOGGER.error("从目录{}加载补丁类{}失败", dir, className, e);
            }
        }
        patchClassLoaders.add(loader);
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
