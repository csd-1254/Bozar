# Bozar Core Migration Strategy

## 1. Reuse the existing engine

The existing Bozar engine is the primary implementation source for transformations. Preserve working transformers and utilities unless they depend directly on GUI/CLI behavior.

## 2. Separate execution from configuration

The existing `Bozar` runner currently owns file validation, JAR reading, class loading, transformation, output writing, verification, and console logging.

Refactor this into a reusable service, conceptually:

```java
ObfuscationEngine
    .execute(ObfuscationRequest request)
        -> ObfuscationResult
```

`ObfuscationRequest` should contain ordinary Java data:

- input JAR
- output JAR
- library paths/classpath
- transformer configuration
- exclusions
- verification settings
- mapping settings
- Minecraft metadata settings

The engine must not know about Gradle.

## 3. Preserve transformers

Inventory every transformer under the existing transformer package.

For each transformer record:

- What it changes.
- Required classpath information.
- Whether it is safe for Minecraft plugins.
- Whether it changes class names.
- Whether it changes resources.
- Whether it requires special keep rules.
- Failure modes.

Do not enable a transformer by default solely because it exists upstream. Establish a tested default profile.

## 4. Resource handling

JAR resources must be preserved unless intentionally transformed.

Pay particular attention to:

- `plugin.yml`
- `paper-plugin.yml`
- `META-INF/services/*`
- manifests
- configuration files
- embedded assets
- signatures

If signed JARs are supported, define the behavior explicitly. If signatures become invalid after bytecode changes, strip signature metadata by default or provide an explicit documented policy.

## 5. Class verification

Keep Bozar's verification capability, but make failures actionable.

A failed verification must identify:

- class name
- transformation stage when known
- exception/cause
- output location

Never silently produce a broken JAR.
