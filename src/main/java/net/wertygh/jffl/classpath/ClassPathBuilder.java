package net.wertygh.jffl.classpath;

import net.wertygh.jffl.env.DevEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ClassPathBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClassPathBuilder.class);
    public static String VERSION_STRING;
    public static final List<String> HARD_CODED_LIBRARIES = List.of(
        "net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-srg.jar",
        "net/minecraftforge/forge/{version}/forge-{version}-universal.jar",
        "net/minecraftforge/forge/{version}/forge-{version}-client.jar",
        "net/minecraftforge/javafmllanguage/{version}/javafmllanguage-{version}.jar",
        "net/minecraftforge/fmlcore/{version}/fmlcore-{version}.jar",
        "net/minecraftforge/mclanguage/{version}/mclanguage-{version}.jar"
    );

    static {buildVersionString();}

    public static void buildVersionString() {
        if (DevEnvironment.isDev()) {
            String mcVersion = getOptionalArg("--fml.mcVersion");
            String forgeVersion = getOptionalArg("--fml.forgeVersion");
            VERSION_STRING = (mcVersion != null && forgeVersion != null) ? mcVersion + "-" + forgeVersion : "dev";
            return;
        }
        String mcVersion = getRequiredArg("--fml.mcVersion");
        String forgeVersion = getRequiredArg("--fml.forgeVersion");
        VERSION_STRING = mcVersion + "-" + forgeVersion;
    }

    public static String buildFullClassPath() {
        Set<String> cp = new LinkedHashSet<>();
        String jvmCp = getJvmClassPath();
        if (jvmCp != null && !jvmCp.isEmpty()) {
            for (String path : jvmCp.split(File.pathSeparator)) {
                if (!path.trim().isEmpty()) cp.add(path);
            }
        }
        if (DevEnvironment.isDev()) {
            addModClassesEntries(cp);
            addLegacyClassPathEntries(cp);
            String gameDir = getOptionalArg("--gameDir");
            if (gameDir == null) gameDir = ".";
            scanModsDir(cp, gameDir);
            LOGGER.info("类路径条目数: {}", cp.size());
            return String.join(File.pathSeparator, cp);
        }
        String libraryDir = getRequiredProperty();
        String gameDir = getRequiredArg("--gameDir");
        Path librariesPath = Paths.get(libraryDir);
        for (String libPath : HARD_CODED_LIBRARIES) {
            String resolved = libPath.replace("{version}", VERSION_STRING);
            Path full = librariesPath.resolve(resolved).normalize();
            if (Files.exists(full)) cp.add(full.toString());
        }
        addLegacyClassPathEntries(cp);
        scanModsDir(cp, gameDir);
        return String.join(File.pathSeparator, cp);
    }

    public static void addModClassesEntries(Set<String> cp) {
        String modClasses = System.getenv("MOD_CLASSES");
        if (modClasses == null || modClasses.isBlank()) return;
        int count = 0;
        for (String entry : modClasses.split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            int sep = entry.indexOf("%%");
            String path = sep >= 0 ? entry.substring(sep + 2) : entry;
            if (!path.isBlank()) {
                cp.add(path);
                count++;
            }
        }
        LOGGER.debug("从MOD_CLASSES添加了{}个类路径条目", count);
    }

    public static void addLegacyClassPathEntries(Set<String> cp) {
        String inline = System.getProperty("legacyClassPath");
        if (inline != null && !inline.isBlank()) {
            for (String path : inline.split(File.pathSeparator)) {
                if (!path.isBlank()) cp.add(path);
            }
        }
        String file = System.getProperty("legacyClassPath.file");
        if (file != null && !file.isBlank()) {
            Path p = Paths.get(file);
            if (Files.isRegularFile(p)) {
                try {
                    for (String line : Files.readAllLines(p)) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) cp.add(trimmed);
                    }
                } catch (Exception e) {
                    LOGGER.debug("读取legacyClassPath.file失败: {}", file, e);
                }
            }
        }
    }

    public static void scanModsDir(Set<String> classPathSet, String gameDir) {
        Path modsDir = Paths.get(gameDir, "mods");
        if (Files.isDirectory(modsDir)) {
            try (var stream = Files.list(modsDir)) {
                stream.filter(p -> p.toString().endsWith(".jar") && Files.isRegularFile(p))
                      .map(Path::toString)
                      .forEach(classPathSet::add);
            } catch (Exception ignored) {}
        }
    }

    public static String getJvmClassPath() {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        return rt.getClassPath();
    }

    public static String getRequiredProperty() {
        String value = System.getProperty("libraryDirectory");
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("缺少必要的系统属性: -DlibraryDirectory");
        }
        return value;
    }

    public static String getRequiredArg(String key) {
        String command = System.getProperty("sun.java.command");
        if (command == null) throw new IllegalStateException("无法获取sun.java.command属性");
        String[] parts = command.split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals(key)) return parts[i + 1];
        }
        throw new IllegalStateException("缺少必要的JVM参数: " + key);
    }

    public static String getOptionalArg(String key) {
        String command = System.getProperty("sun.java.command");
        if (command == null) return null;
        String[] parts = command.split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals(key)) return parts[i + 1];
        }
        return null;
    }
}
