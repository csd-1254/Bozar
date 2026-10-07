# Minecraft Plugin Support

## 1. Scope

The first-class target is Bukkit/Spigot/Paper-style plugin JARs.

Support must not assume that every Minecraft project has identical metadata.

## 2. Metadata files

Handle:

- `plugin.yml`
- `paper-plugin.yml`

Preserve all unrelated metadata fields and formatting semantics as far as practical.

## 3. Entrypoint handling

If the plugin main class is:

```yaml
main: com.example.MyPlugin
```

and the obfuscator renames it to another class, the final metadata must point to the renamed class.

There are two valid strategies:

1. Keep the entrypoint class name unchanged.
2. Rename it and rewrite metadata.

Keeping the entrypoint is safer as a default; rewriting is useful for aggressive obfuscation. The chosen default must be explicit.

## 4. Reflection-sensitive code

Minecraft plugins frequently use:

- reflection
- string-based class names
- service loading
- event registration
- command registration
- configuration-defined class names

Do not claim that arbitrary reflection can be detected perfectly. Provide explicit keep rules and document limitations.

## 5. External APIs

Do not rename or modify Bukkit/Paper/Minecraft API classes that are supplied by the runtime or dependency classpath.

The obfuscator should normally transform only the plugin's own classes unless explicitly configured otherwise.

## 6. Keep rules

Provide convenient Minecraft rules for:

- plugin entrypoint
- explicitly named public API packages
- classes referenced from configuration
- service providers
- reflection-sensitive classes

## 7. Integration tests

Create a minimal synthetic Minecraft plugin JAR containing:

- main plugin class
- listener
- command executor
- nested class
- resource file
- `plugin.yml`
- optional `paper-plugin.yml`
- reflection example

Run obfuscation and verify:

- output JAR opens successfully
- metadata exists
- metadata entrypoint resolves
- transformed classes load with an appropriate test classloader
- resources remain present
- excluded classes retain their names
