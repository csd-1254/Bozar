# Bozar Gradle Plugin

Bytecode obfuscation for Java artifacts, with first-class Bukkit/Paper plugin metadata
handling. A real Gradle plugin around the upstream Bozar transformation engine — no GUI,
no CLI, no external process.

```groovy
plugins {
    id 'java'
    id 'io.github.vimasig.bozar' version '0.1.0'
}
```

`./gradlew build` then produces `build/libs/<name>-obfuscated.jar` alongside the normal
unobfuscated jar. The input jar is never modified.

## Usage

```groovy
bozar {
    // NONE | BASIC | STANDARD | AGGRESSIVE — see docs/05-obfuscation-profiles.md
    profileName = 'BASIC'          // default: rename only

    enabled = true                 // false skips the task without removing it from the graph
    verify = true                  // ASM structural verification after transformation

    // Keep rules, translated to engine exclusion patterns
    keep 'com.example.MyPlugin'
    keepPackage 'com.example.api'

    minecraft {
        enabled = true             // reads plugin.yml / paper-plugin.yml from the input jar
    }

    // KEEP = metadata stays put because the entrypoint class is kept; REWRITE = the
    // entrypoint is renamed and plugin.yml / paper-plugin.yml are rewritten to match
    entrypointMode = 'KEEP'                 // or 'REWRITE'
    keepServiceProviders = true      // keep META-INF/services entries from renaming

    naming {
        seed = 123456L             // same input + same seed => same output content (not bytes; entry timestamps are wall-clock)
    }

    renamer     { enabled = true }
    controlFlow { enabled = false }
    constants   { enabled = false }

    debug {
        removeSourceFile = true
        removeLineNumbers = true
        removeLocalVariables = true
    }
}
```

### Entrypoint handling

| Mode | Behavior |
| --- | --- |
| `KEEP` (default) | The entrypoint class from `plugin.yml`/`paper-plugin.yml` is added to the keep rules, so metadata stays correct with no rewriting. |
| `REWRITE` | No keep rule is added; the class is renamed and the metadata is rewritten by the engine's `ClassRenamerTransformer`, which substitutes dotted class names inside resources. |

### Notable defaults

- Profile `BASIC` — renaming only. Control flow, constant/string obfuscation, shuffling
  and watermarks are opt-in because they are the transformations most likely to break a
  Minecraft plugin at runtime.
- `minecraft.enabled = false`. A general-purpose obfuscator must not silently keep classes
  on a project that has nothing to do with Minecraft.
- Mapping file at `build/bozar/mapping.txt`.

## Requirements

| Item | Value |
| --- | --- |
| Java to build the plugin | 17 |
| Minimum Java runtime | 17 |
| Class-file versions tested | up to Java 26 (major 70) — measured against ASM 9.9 |
| ASM | 9.9 (pinned to the upstream version) |
| Gradle | 8.14.5 (the version the wrapper pins and the tests run against) |

The plugin builds for Java 17 while the engine passes through whatever class-file version
the input contains (measured up to Java 26 / major 70; major 71 is rejected by ASM): the
build JDK and the Minecraft server JDK are different machines.

## Build cache and configuration cache

`ObfuscateTask` is `@CacheableTask` and configuration-cache clean; both are covered by
TestKit tests (`BozarPluginTest#buildCacheReusesTheObfuscatedJar`,
`#configurationCacheWorks`). Every value that can change the output bytes — profile,
toggles, seed, keep patterns, classpath — is a declared task input, so a seed change is a
cache miss rather than a silent rename drift.

## Limitations

See [`docs/12-deviations-and-limitations.md`](docs/12-deviations-and-limitations.md) for the
full list, including which transformers honor a seed, the textual (non-YAML) metadata
reader, and the license situation of the upstream source.

## Building this repository

```bash
./gradlew clean build     # requires JAVA_HOME pointing at a JDK 17
```

## License

MIT, Copyright (c) 2020-present vimasig (Vedat Sazoglu). See `LICENSE`.

---

## Implementation specification (original project documentation)

This documentation defines the implementation plan for converting Bozar into a fully native Gradle plugin focused on Java Minecraft plugin obfuscation.

### Start here

1. Read `prompt.md`.
2. Read every file in `docs/`.
3. Start with `docs/00-project-overview.md`.
4. Follow the phases in `docs/09-implementation-phases.md`.
5. Use `docs/10-acceptance-checklist.md` before declaring the implementation complete.

### Important

These documents are an implementation specification, not a request to wrap the existing Bozar executable. The target is a real Gradle plugin with a reusable obfuscation core and first-class Minecraft metadata support.
