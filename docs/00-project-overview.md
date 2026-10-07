# Bozar Gradle Plugin — Project Overview

## 1. Objective

Convert Bozar from a desktop/CLI-oriented Java bytecode obfuscator into a **fully native Gradle plugin** whose primary use case is building and obfuscating Java Minecraft plugins.

The result must be usable from a normal Gradle build without launching a GUI, invoking a separate executable, or manually passing input/output paths.

Target developer experience:

```kotlin
plugins {
    id("io.github.vimasig.bozar") version "<version>"
}

bozar {
    minecraft {
        enabled.set(true)
    }
}
```

Then:

```bash
./gradlew build
```

produces the normal JAR and an obfuscated JAR according to the plugin configuration.

## 2. Upstream basis

The upstream Bozar project is a Java bytecode obfuscator using ASM. Its current source separates the obfuscation engine under `io.github.vimasig.bozar.obfuscator` from the UI under `io.github.vimasig.bozar.ui`.

The existing engine already provides a `Bozar` execution class, `TransformManager`, class transformers, configuration objects, ASM utilities, resource handling, and verification. These should be reused rather than replaced unnecessarily.

## 3. Primary design principle

The project is not a Gradle wrapper around `java -jar Bozar`.

It must be a Gradle plugin:

```text
Gradle Project
    |
    +-- Bozar Gradle Plugin
          |
          +-- Extension / DSL
          +-- Gradle Task(s)
          +-- Obfuscation Core
          +-- Minecraft Metadata Processor
          +-- Verification
```

## 4. Non-goals

- JavaFX GUI support as part of the Gradle plugin.
- Requiring users to configure absolute input/output file paths.
- Shelling out to Maven.
- Shelling out to a separate Bozar executable.
- Reimplementing every existing transformer without need.
- Obfuscating Bukkit/Paper APIs or external dependency classes unless explicitly supported later.

## 5. Deliverable

The repository should build a Gradle plugin artifact that can be consumed through the Gradle Plugin DSL or included in another Gradle build using `includeBuild` during development.

The implementation must include automated tests and documentation for configuration, task behavior, Minecraft metadata, exclusions, troubleshooting, and release verification.
