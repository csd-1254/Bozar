# Architecture and Module Boundaries

## 1. Recommended modules

Prefer a multi-module structure if practical:

```text
bozar/
├── bozar-core/
├── bozar-gradle-plugin/
└── bozar-test-fixtures/        # optional
```

If keeping a single Gradle project is materially simpler, preserve the same logical package boundaries.

## 2. Core module

The core module owns:

- ASM processing.
- Class loading.
- `ClassNode` transformation.
- Transformer discovery/execution.
- Obfuscation configuration independent of Gradle.
- JAR/resource processing.
- Verification.
- Name mapping.

The core must not import Gradle classes.

## 3. Gradle plugin module

The plugin module owns:

- `Plugin<Project>` implementation.
- Gradle extension.
- Gradle task(s).
- `Property`, `ListProperty`, `SetProperty`, `RegularFileProperty`, and `DirectoryProperty` usage.
- Task dependency wiring.
- Gradle logging.
- Worker API usage if introduced.
- Configuration/cache semantics.

## 4. Minecraft integration

Minecraft-specific behavior should be isolated from generic bytecode obfuscation.

Responsibilities:

- Detect `plugin.yml`.
- Detect `paper-plugin.yml`.
- Identify the plugin entrypoint.
- Prevent accidental loss of required metadata.
- Update metadata when a class is renamed.
- Apply keep rules for entrypoints.
- Preserve non-class resources unless explicitly configured otherwise.

## 5. Data flow

```text
Gradle configuration
        |
        v
BozarExtension
        |
        v
Validated immutable execution configuration
        |
        v
BozarTask
        |
        v
Core obfuscation engine
        |
        +--> Read JAR
        +--> Read classes/resources
        +--> Build classpath
        +--> Apply transformers
        +--> Rewrite Minecraft metadata
        +--> Write JAR
        +--> Verify output
        |
        v
Obfuscated JAR
```

## 6. Isolation rule

Do not allow Gradle `Project` objects to leak into the core. The core should receive ordinary Java configuration/data objects.
