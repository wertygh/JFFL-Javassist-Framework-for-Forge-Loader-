package net.wertygh.jffl.env;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DevEnvironment {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevEnvironment.class);
    private static final boolean DEV;
    static {DEV = detect();LOGGER.info("当前{}环境", DEV ? "开发" : "生产");}
    public static boolean isDev() {return DEV;}
    public static boolean isProduction() {return !DEV;}

    private static boolean detect() {
        String override = System.getProperty("jffl.dev");
        if (override != null) {
            boolean forced="true".equalsIgnoreCase(override.trim())||"1".equals(override.trim());
            return forced;
        }
        if ("true".equals(System.getProperty("fml.deobfuscatedEnvironment"))) return true;
        String modClasses = System.getenv("MOD_CLASSES");
        if (modClasses!=null&&!modClasses.isBlank()&&modClasses.contains("%%"))return true;
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.contains("forgegradle") || classpath.contains("fg_cache")
           ||classpath.contains("userdev")||classpath.contains("-sources"))return true;
        String command = System.getProperty("sun.java.command", "");
        if (command.contains("userdev") || command.contains("GradleStart")
           || command.contains("net.minecraftforge.userdev")
           || command.contains("net.minecraftforge.gradle")) return true;
        String launchTarget = findArg(command, "--launchTarget");
        if (launchTarget != null
            &&(launchTarget.contains("userdev")||launchTarget.contains("dev")))return true;
        String naming = findArg(command, "--fml.naming");
        if ("mcp".equals(naming)||"mojang".equals(naming)||"mojmap".equals(naming))return true;
        if (classExists("net.minecraftforge.userdev.LaunchTesting")
            || classExists("net.minecraftforge.gradle.userdev.UserDevPlugin")
            || classExists("net.minecraftforge.gradle.GradleStart")) return true;
        return false;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, DevEnvironment.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {return false;}
    }

    private static String findArg(String command, String key) {
        if (command == null) return null;
        String[] parts = command.split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals(key)) return parts[i + 1];
        }
        return null;
    }
}
