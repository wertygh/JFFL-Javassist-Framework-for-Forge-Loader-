## JFFL - Javassist Framework for Forge Loader

JFFL is a bytecode manipulation library for Minecraft Forge based on Javassist, providing Mixin-like declarative patch injection capabilities. It allows developers to modify classes from Minecraft and other mods at runtime using annotations, without the need to write low-level ASM or Javassist code directly.

---

## Features

· Declarative Annotation-Driven
· Callback Handlers
· Mapping Remapping Support
· Flexible Injection Points

---

## Installation and Dependencies

```gradle
repositories {
    // Put the .jar of JFFL mod into the libs folder
    flatDir {
        dir 'libs'
    }
}
```

---

## Relationship with Mixin

JFFL is not a replacement for Mixin but rather provides an alternative option. Both can coexist, though applying both to the same class is not recommended as it may lead to unexpected conflicts. JFFL's advantages include:

· Lightweight, based on Javassist source-level operations, easier to debug
· Supports dynamic callback registration without generating synthetic methods

---

## Development and Debugging

In development environment (jffl.dev=true):

· JFFL outputs detailed logs
· Use @DumpClass to output modified classes to the .jffl-dump directory
· Supports direct use of MCP/Mojang names without loading mappings

---

## License

BSD Zero Clause License

---

## Contribution and Feedback

Issues and Pull Requests are welcome!
Project author: wertygh



## JFFL - Javassist Framework for Forge Loader

JFFL 是一个基于 [Javassist](https://www.javassist.org/) 的Minecraft Forge字节码操作库，提供类似Mixin的声明式补丁注入能力。它允许开发者通过注解在运行时修改Minecraft及其他模组的类，而无需直接编写ASM或Javassist底层代码。

---

## 特性

- **声明式注解驱动**
- **回调处理器**
- **映射重映射支持**
- **灵活的注入点**

---

## 安装与依赖

```gradle
repositories {
    // JFFL模组的.jar放入libs文件夹
    flatDir {
        dir 'libs'
    }
}
```

---

## 与Mixin的关系

JFFL并非Mixin的替代品，而是提供另一种选择。两者可以共存，但不推荐对同一类同时使用Mixin和JFFL，可能导致不可预期的冲突。JFFL的优势在于：
· 更轻量，基于Javassist源码级操作，易于调试
· 支持动态回调注册，无需生成合成方法

---

## 开发与调试

在开发环境（jffl.dev=true）下：
· JFFL会输出详细日志
· 使用@DumpClass可将修改后的类输出到.jffl-dump目录
· 支持直接使用MCP/Mojang名称，无需加载映射

---

## 许可证

BSD Zero Clause License

---

## 贡献与反馈

欢迎提交 Issue 和 Pull Request！
项目作者：wertygh
