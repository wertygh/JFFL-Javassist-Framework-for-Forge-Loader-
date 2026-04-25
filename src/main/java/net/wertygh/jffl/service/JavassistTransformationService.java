package net.wertygh.jffl.service;

import net.wertygh.jffl.classpath.ClassPathBuilder;
import net.wertygh.jffl.engine.PatchEngine;
import net.wertygh.jffl.env.DevEnvironment;
import net.wertygh.jffl.registry.PatchRegistry;
import cpw.mods.modlauncher.api.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

public class JavassistTransformationService implements ITransformationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JavassistTransformationService.class);
    private final PatchRegistry registry = new PatchRegistry();
    private PatchEngine engine;

    @Override public @NotNull String name() {return "jffl_javassist";}

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
        LOGGER.info("JFFL(Javassist Framework for Forge Loader)加载中");
        if (DevEnvironment.isDev()) {
            LOGGER.info("JFFL跳过SRG映射加载, 使用MCP/Mojang名称");
        }
    }

    @Override
    public void initialize(IEnvironment environment) {
        LOGGER.info("JFFL正在初始化, 扫描补丁中");
        int before = registry.getTargetClasses().size();
        if (DevEnvironment.isDev()) {
            String modClasses = System.getenv("MOD_CLASSES");
            if (modClasses != null && !modClasses.isBlank()) {
                registry.scanModClasses(modClasses);
                LOGGER.info("已扫描MOD_CLASSES目录来源");
            } else {
                LOGGER.info("MOD_CLASSES未设置, 退到java.class.path");
            }
            String jvmCp = ClassPathBuilder.getJvmClassPath();
            if (jvmCp != null && !jvmCp.isEmpty()) {
                registry.scanClassPath(jvmCp);
            }
            File modsDir = new File("mods");
            if (modsDir.isDirectory()) {
                registry.scanDirectory(modsDir);
            }
        } else {
            File modsDir = new File("mods");
            registry.scanDirectory(modsDir);
        }

        int discovered = registry.getTargetClasses().size() - before;
        engine = new PatchEngine(registry);
        try {
            String fullCp = ClassPathBuilder.buildFullClassPath();
            int cpEntries = 0;
            for (String entry : fullCp.split(File.pathSeparator)) {
                if (entry.isBlank()) continue;
                engine.appendClassPath(entry);
                cpEntries++;
            }
            LOGGER.debug("为ClassPool添加{}个类路径条目", cpEntries);
        } catch (Exception e) {
            if (DevEnvironment.isDev()) {
                LOGGER.info("ClassPool类路径构建出现异常: {}", e.getMessage());
            } else {
                LOGGER.warn("无法为Javassist ClassPool构建完整的类路径", e);
            }
        }
        if (!DevEnvironment.isProduction()) {
            LOGGER.info("当前开发环境, 跳过SRG映射加载");
        }
        LOGGER.info("JFFL已初始化：{}个补丁目标类, {}个插件",
                registry.getTargetClasses().size(), registry.getTransformerPlugins().size());
    }

    @Override
    public @NotNull List<ITransformer> transformers() {
        Set<String> targets = registry.getTransformerTargetClasses();
        List<ITransformer> out = new ArrayList<>();
        if (DevEnvironment.isProduction()) {
            out.add(new JavassistRemapperTransformer());
        }
        if (!targets.isEmpty()) {
            out.add(new JavassistTransformer(targets, engine, registry.getTransformerPlugins()));
        } else if (registry.hasTransformerPlugins()) {
            LOGGER.warn("已注册插件, 但没有可挂载目标类; 请让插件targetClasses()返回目标类, 或注册至少一个JFFL补丁目标, 不然你注册这个插件的意义是什么");
        } else {
            LOGGER.info("未注册任何用户补丁");
        }
        if (out.isEmpty()) {
            return List.of();
        }
        return List.copyOf(out);
    }

    public PatchRegistry getRegistry() {return registry;}

    public PatchEngine getEngine() {return engine;}
}
