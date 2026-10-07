# Quality, Compatibility, and Security

## 1. Java compatibility

The implementation must explicitly document:

- Java version used to build the plugin.
- Lowest supported runtime Java version.
- Maximum tested class-file version.
- ASM version.
- Gradle versions tested.

Do not assume the Java version used to build the plugin is identical to the Java version used by the Minecraft server.

## 2. Gradle compatibility

Test a supported Gradle range rather than only the latest Gradle release.

Avoid deprecated APIs where practical.

## 3. Minecraft compatibility

Test representative Paper/Bukkit-style plugins. The plugin must not claim universal compatibility without evidence.

## 4. Signed JARs

Bytecode rewriting normally invalidates existing JAR signatures. Define and test the policy explicitly. The safest default is to avoid emitting stale signature files after modification unless a valid signing workflow is intentionally implemented.

## 5. Reproducible builds

Where possible:

- stable class/resource ordering
- stable naming seed
- stable metadata serialization
- no timestamps that unnecessarily change output

If exact byte-for-byte reproducibility is not possible, document why.

## 6. Security boundaries

Obfuscation is not encryption and is not a guarantee against reverse engineering.

Do not describe decompiler-crashing or control-flow techniques as mathematically preventing reverse engineering.

## 7. Licensing

Preserve the upstream Bozar license and required notices. Clearly document modifications and the relationship to upstream.
