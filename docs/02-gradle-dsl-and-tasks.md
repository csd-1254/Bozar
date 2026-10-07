# Gradle DSL, Tasks, and Build Integration

## 1. Plugin application

The plugin must support the standard Gradle Plugin DSL:

```kotlin
plugins {
    id("io.github.vimasig.bozar") version "..."
}
```

## 2. Extension design

Use lazy Gradle properties. Avoid eager `Project` reads during configuration.

**Actual DSL:**

```kotlin
bozar {
    enabled.set(true)

    inputJar.set(tasks.jar.flatMap { it.archiveFile })
    outputJar.set(layout.buildDirectory.file("libs/${project.name}-obfuscated.jar"))

    minecraft {
        enabled.set(true)
        // No higher-level minecraft options; entrypoint handling is through entrypointMode
        // and keep/keepPackage rules
    }

    renamer {
        enabled.set(true)    // Enables ClassRenamerTransformer, default in BASIC profile
    }

    controlFlow {
        enabled.set(true)    // Enables ControlFlowObfuscationOption.LIGHT
    }

    constants {
        enabled.set(true)    // Enables ConstantObfuscationOption.LIGHT and StringObfuscationOption.LIGHT
    }

    debug {
        removeSourceFile.set(true)
        removeLineNumbers.set(true)
        removeLocalVariables.set(true)
    }

    classpath.from(file("bukkit-api.jar"))  // Add libraries the remapper must resolve

    profileName = "BASIC"
    verify = true
    entrypointMode = "KEEP"
    keep("com.example.MyPlugin")
    keepPackage("com.example.api")
    keepServiceProviders = true
    
    naming {
        seed = 123456L
    }
}
```

**Historical note:** The DSL shown in earlier documentation included options that were never implemented (`pluginYml`, `paperPluginYml`, `keepEntrypoint`, `updateEntrypoint` in `minecraft {}`) or whose behavior was never finalized (`exclude {}` block). These have no effect and should not be used.

The extension provides lazy typing and discoverability, with dedicated subsections for debug and naming options.

## 3. Tasks

At minimum provide a dedicated task such as:

```bash
./gradlew obfuscate
```

The task must declare proper inputs and outputs.

Expected dependency:

```text
obfuscate
   dependsOn jar
```

`build` should depend on `obfuscate` when obfuscation is enabled, or the plugin should provide an explicit opt-in integration mode. The chosen behavior must be documented and tested.

## 4. Gradle task correctness

Use:

- `@Input`, `@InputFile`, `@InputFiles`, `@OutputFile`, etc. as appropriate.
- `@PathSensitive` where required.
- `@CacheableTask` only when the task is genuinely cache-correct.
- `Property<T>` and `RegularFileProperty` rather than mutable fields.
- Gradle `Logger` rather than direct `System.out` for plugin-level messages.

Do not mark a task cacheable until all relevant configuration, input JARs, libraries, mapping seeds, and transformer settings are represented in task inputs.

## 5. Configuration cache

Avoid accessing `Project`, configurations, environment state, or mutable global state inside `@TaskAction` unless supported by Gradle's configuration-cache model.

Resolve values into task properties during configuration/execution using supported Gradle APIs.

## 6. Output behavior

Default output should be deterministic and clearly named, for example:

```text
build/libs/MyPlugin-1.0.0-obfuscated.jar
```

Never overwrite the original JAR by default.