package net.wertygh.jffl.engine;

import net.wertygh.jffl.env.DevEnvironment;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class MappingLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(MappingLoader.class);
    private static final String MCG_CLASS = "javassist.compiler.MemberCodeGen";
    private static volatile boolean loaded = false;
    private static volatile int fieldCount = 0;
    private static volatile int methodCount = 0;
    private static Method addFieldMapping;
    private static Method addMethodMapping;
    private static Method addMethodDescMapping;
    private static Method clearMappings;
    
    public static void loadFromCustomBzip2Resource(String resourcePath) {
        InputStream is = MappingLoader.class.getResourceAsStream(resourcePath);
        if (is == null) {
            is = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath);
        }
        if (is == null) {
            LOGGER.warn("找不到mappings: {}", resourcePath);
            return;
        }
        try (BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(is);
             BufferedReader reader = new BufferedReader(new InputStreamReader(bzIn, StandardCharsets.UTF_8))) {
            loadFromCustomFormat(reader);
            LOGGER.info("加载mappings：{}", resourcePath);
        } catch (Exception e) {
            LOGGER.error("无法从{}加载mappings", resourcePath, e);
        }
    }

    private static void loadFromCustomFormat(BufferedReader reader) throws IOException, ReflectiveOperationException {
        ensureReflection();
        Deque<ClassContext> stack = new ArrayDeque<>();
        String line;
        int fields = 0, methods = 0;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            int indent = countLeadingSpaces(line);
            String content = line.trim();
            if (content.isEmpty()) continue;
    
            if (content.startsWith("C ")) {
                String className = content.substring(2).trim();
                stack.clear();
                stack.push(new ClassContext(className, indent));
                continue;
            }
            if (content.startsWith("$")) {
                String innerName = content.substring(1).trim();
                while (!stack.isEmpty() && stack.peek().indent >= indent) {
                    stack.pop();
                }
                if (stack.isEmpty()) {
                    throw new IllegalStateException("内部类 $" + innerName + " 没有父类, 缩进级别=" + indent);
                }
                String parentPath = stack.peek().classPath;
                String fullInnerPath = parentPath + "$" + innerName;
                stack.push(new ClassContext(fullInnerPath, indent));
                continue;
            }
            if (content.startsWith("F ")) {
                String[] parts = content.substring(2).trim().split("\\s+");
                if (parts.length < 2) {
                    LOGGER.warn("无效字段行: {}", content);
                    continue;
                }
                String obfName = parts[0];
                String officialName = parts[1];
                if (stack.isEmpty()) {
                    LOGGER.warn("字段{}不属于任何类", obfName);
                    continue;
                }
                String classPath = stack.peek().classPath;
                String owner = classPath.replace('/', '.');
                addFieldMappingInternal(owner, obfName, officialName);
                fields++;
                continue;
            }
            if (content.startsWith("M ")) {
                String[] parts = content.substring(2).trim().split("\\s+");
                if (parts.length < 3) {
                    LOGGER.warn("无效方法行: {}", content);
                    continue;
                }
                String obfName = parts[0];
                String officialName = parts[1];
                StringBuilder descBuilder = new StringBuilder(parts[2]);
                for (int i = 3; i < parts.length; i++) {
                    descBuilder.append(" ").append(parts[i]);
                }
                String descriptor = descBuilder.toString();
                if (stack.isEmpty()) {
                    LOGGER.warn("方法{}不属于任何类", obfName);
                    continue;
                }
                String classPath = stack.peek().classPath;
                String owner = classPath.replace('/', '.');
                addMethodMappingInternal(owner, obfName, officialName, descriptor);
                methods++;
                continue;
            }
        }
    
        fieldCount += fields;
        methodCount += methods;
        loaded = true;
        LOGGER.info("加载mappings：{}个字段, {}个方法", fields, methods);
    }

    private static void addFieldMappingInternal(String owner, String obfName, String officialName) {
        try {
            String mcpKey = owner + "." + officialName;
            addFieldMapping.invoke(null, mcpKey, obfName);
        } catch (Exception e) {
            LOGGER.warn("无法添加字段映射{}.{} -> {}", owner, obfName, officialName, e);
        }
    }

    private static void addMethodMappingInternal(String owner, String obfName, String officialName, String descriptor) {
        try {
            String mcpSimpleKey = owner + "." + officialName;
            addMethodMapping.invoke(null, mcpSimpleKey, obfName);
            int argCount = countDescriptorArgs(descriptor);
            if (argCount >= 0) {
                String mcpOverloadKey = owner + "." + officialName + "#" + argCount;
                addMethodDescMapping.invoke(null, mcpOverloadKey, obfName);
            }
        } catch (Exception e) {
            LOGGER.warn("无法添加方法映射{}.{} -> {}", owner, obfName, officialName, e);
        }
    }

    private static int countLeadingSpaces(String s) {
        int count = 0;
        while (count < s.length() && s.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static class ClassContext {
        final String classPath;
        final int indent;
        ClassContext(String classPath, int indent) {
            this.classPath = classPath;
            this.indent = indent;
        }
    }

    public static void loadFromFile(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            loadFromStream(is);
        } catch (Exception e) {
            LOGGER.error("无法从{}加载映射", path, e);
        }
    }
    
    public static void loadFromFile(File file) {
        loadFromFile(file.toPath());
    }
    
    public static void loadFromStream(InputStream inputStream) {
        Objects.requireNonNull(inputStream, "inputStream不能为空");
        try {
            ensureReflection();
        } catch (Exception e) {
            LOGGER.error("无法初始化MemberCodeGen反射, 什么玩意儿?", e);
            return;
        }

        int fields = 0, methods = 0, skipped = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("FD: ")) {
                    if (parseFieldLine(line)) fields++;
                    else skipped++;
                } else if (line.startsWith("MD: ")) {
                    if (parseMethodLine(line)) methods++;
                    else skipped++;
                }
            }
        } catch (IOException e) {
            LOGGER.error("发生I/O错误", e);
        }
        fieldCount += fields;
        methodCount += methods;
        loaded = true;
    }
    
    public static void loadFromResource(String resourcePath) {
        InputStream is = MappingLoader.class.getResourceAsStream(resourcePath);
        if (is == null) {
            is = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(resourcePath.startsWith("/")
                            ? resourcePath.substring(1) : resourcePath);
        }
        if (is == null) {
            LOGGER.error("未找到映射：{}", resourcePath);
            return;
        }
        try (InputStream ignored = is) {
            loadFromStream(is);
            LOGGER.info("已从{}加载SRG 映射", resourcePath);
        } catch (IOException e) {
            LOGGER.error("无法关闭{}的资源流", resourcePath, e);
        }
    }
    
    public static void addField(String className, String srgName, String mappedName) {
        try {
            ensureReflection();
            String key = className + "." + srgName;
            addFieldMapping.invoke(null, key, mappedName);
        } catch (Exception e) {
            LOGGER.error("添加字段映射{}.{} -> {}失败", className, srgName, mappedName, e);
        }
    }
    
    public static void addMethod(String className, String srgName, String mappedName) {
        try {
            ensureReflection();
            String key = className + "." + srgName;
            addMethodMapping.invoke(null, key, mappedName);
        } catch (Exception e) {
            LOGGER.error("添加方法映射{}.{} -> {}失败", className, srgName, mappedName, e);
        }
    }
    
    public static void addMethodOverload(String className, String srgName, int argCount, String mappedName) {
        try {
            ensureReflection();
            String key = className + "." + srgName + "#" + argCount;
            addMethodDescMapping.invoke(null, key, mappedName);
        } catch (Exception e) {
            LOGGER.error("添加方法重载映射{}.{}#{} -> {}失败", className, srgName, argCount, mappedName, e);
        }
    }
    
    public static void clearAll() {
        try {
            ensureReflection();
            clearMappings.invoke(null);
            loaded = false;
            fieldCount = 0;
            methodCount = 0;
        } catch (Exception e) {
            LOGGER.error("未能清除映射", e);
        }
    }
    
    public static boolean isLoaded() {return loaded;}
    public static int getFieldCount() {return fieldCount;}
    public static int getMethodCount() {return methodCount;}

    private static boolean parseFieldLine(String line) {
        String[] parts = line.substring(4).trim().split("\\s+");
        if (parts.length < 2) {
            LOGGER.warn("格式错误的FD行: {}", line);
            return false;
        }
        String srgFull = parts[0];
        String mappedFull = parts[1];
        int srgSlash = srgFull.lastIndexOf('/');
        int mappedSlash = mappedFull.lastIndexOf('/');
        if (srgSlash < 0 || mappedSlash < 0) {
            LOGGER.warn("FD行缺少所有者分隔符: {}", line);
            return false;
        }
        String owner = srgFull.substring(0, srgSlash).replace('/', '.');
        String srgName = srgFull.substring(srgSlash + 1);
        String mappedName = mappedFull.substring(mappedSlash + 1);
        if (srgName.equals(mappedName)) return false;
        try {
            String mcpKey = owner + "." + mappedName;
            addFieldMapping.invoke(null, mcpKey, srgName);
            return true;
        } catch (Exception e) {
            LOGGER.warn("注册字段映射失败: {}", line, e);
            return false;
        }
    }

    private static boolean parseMethodLine(String line) {
        String[] parts = line.substring(4).trim().split("\\s+");
        if (parts.length < 4) {
            LOGGER.warn("格式错误的MD行: {}", line);
            return false;
        }
        String srgFull = parts[0];
        String srgDesc = parts[1];
        String mappedFull = parts[2];
        int srgSlash = srgFull.lastIndexOf('/');
        int mappedSlash = mappedFull.lastIndexOf('/');
        if (srgSlash < 0 || mappedSlash < 0) {
            LOGGER.warn("MD行缺少所有者分隔符: {}", line);
            return false;
        }
        String owner = srgFull.substring(0, srgSlash).replace('/', '.');
        String srgName = srgFull.substring(srgSlash + 1);
        String mappedName = mappedFull.substring(mappedSlash + 1);
        if (srgName.equals(mappedName)) return false;
        try {
            String mcpSimpleKey = owner + "." + mappedName;
            addMethodMapping.invoke(null, mcpSimpleKey, srgName);
        
            int argCount = countDescriptorArgs(srgDesc);
            if (argCount >= 0) {
                String mcpOverloadKey = owner + "." + mappedName + "#" + argCount;
                addMethodDescMapping.invoke(null, mcpOverloadKey, srgName);
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("注册方法映射失败: {}", line, e);
            return false;
        }
    }
    
    static int countDescriptorArgs(String desc) {
        if (desc == null || desc.isEmpty() || desc.charAt(0) != '(') return -1;
        int count = 0;
        int i = 1;
        while (i < desc.length()) {
            char c = desc.charAt(i);
            if (c == ')') break;
            count++;
            switch (c) {
                case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> i++;
                case 'L' -> {
                    int semi = desc.indexOf(';', i);
                    if (semi < 0) return -1;
                    i = semi + 1;
                }
                case '[' -> {
                    count--;
                    i++;
                }
                default -> {return -1;}
            }
        }
        return count;
    }
    
    private static synchronized void ensureReflection() throws ReflectiveOperationException {
        if (addFieldMapping != null) return;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = MappingLoader.class.getClassLoader();
        Class<?> mcg;
        try {
            mcg = Class.forName(MCG_CLASS, true, cl);
        } catch (ClassNotFoundException e) {
            LOGGER.warn("通过context ClassLoader加载{}失败(这咋失败的?)", MCG_CLASS);
            mcg = Class.forName(MCG_CLASS, true, MappingLoader.class.getClassLoader());
        }
        try {
            addFieldMapping = mcg.getMethod("jffl$addFieldMapping", String.class, String.class);
            addMethodMapping = mcg.getMethod("jffl$addMethodMapping", String.class, String.class);
            addMethodDescMapping = mcg.getMethod("jffl$addMethodDescMapping", String.class, String.class);
            clearMappings = mcg.getMethod("jffl$clearMappings");
        } catch (NoSuchMethodException e) {
            LOGGER.error("加载到的{}来自{}, 缺少jffl$方法.", MCG_CLASS, mcg.getClassLoader());
            throw e;
        }
        LOGGER.debug("MappingLoader反射已针对{}初始化", MCG_CLASS, mcg.getClassLoader());
    }

    public static void autoDiscover() {
        if (DevEnvironment.isDev()) {
            LOGGER.info("跳过映射自动发现, 类名已经是MCP/Mojang名称");
            return;
        }
        String customResource = "/assets/mappings/mappings.bz2";
        if (MappingLoader.class.getResource(customResource) != null) {
            loadFromCustomBzip2Resource(customResource);
            if (loaded) return;
        }
        InputStream res = MappingLoader.class.getResourceAsStream("/mappings/mappings.srg");
        if (res == null) {
            res = Thread.currentThread().getContextClassLoader().getResourceAsStream("mappings/mappings.srg");
        }
        if (res != null) {
            try (InputStream ignored = res) {
                loadFromStream(res);
            } catch (IOException e) {
                LOGGER.warn("关闭资源流失败", e);
            }
            if (loaded) return;
        }
        Path configPath = Path.of("config", "jffl", "mappings.srg");
        if (Files.isReadable(configPath)) {
            loadFromFile(configPath);
            return;
        }
        LOGGER.info("未找到映射文件");
    }
}