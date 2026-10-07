# Obfuscation Profiles and Defaults

## 1. Principle

A Minecraft plugin obfuscator should favor runtime reliability over maximum transformation count.

Provide named profiles if useful:

```text
NONE
BASIC
STANDARD
AGGRESSIVE
```

The exact names are implementation details, but the behavior must be documented.

## 2. Candidate transformations

The upstream project currently advertises capabilities including:

- watermark
- renamer
- shuffler
- decompiler crasher
- control-flow obfuscation
- constant obfuscation for strings/numbers
- line-number obfuscation
- local-variable obfuscation
- inner-class removal
- source/debug removal

Each must be tested independently and in combinations.

## 3. Safe default

The default profile should not enable the most fragile transformations without testing against representative Minecraft plugins.

At minimum test:

- Paper API usage
- Bukkit events
- commands
- lambdas
- anonymous classes
- records if supported
- modern Java bytecode versions supported by the plugin
- reflection
- resources

## 4. Determinism

If the renamer is deterministic, expose a seed or stable naming policy.

Example:

```kotlin
bozar {
    naming {
        seed.set(123456L)
    }
}
```

Changing the seed should intentionally change generated names. The seed must be part of task inputs if build caching is enabled.

## 5. Mapping

Provide an optional mapping output for diagnostics:

```text
build/bozar/mapping.txt
```

Never expose sensitive configuration values in the mapping.
