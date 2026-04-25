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
// Write in dependencies (replace {versino} with the actual version number):
dependencies {
    implementation fg.deobf("wertygh:jffl:{version}")
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
