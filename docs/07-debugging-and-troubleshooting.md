# Debugging and Troubleshooting

## 1. General rule

When an obfuscated plugin fails at runtime, first determine whether the failure is caused by:

1. bytecode transformation,
2. renamed symbols,
3. missing metadata/resource,
4. reflection/string references,
5. classpath resolution,
6. unsupported bytecode version,
7. invalid Gradle task wiring.

Do not immediately disable all obfuscation.

## 2. Useful diagnostic modes

Provide a verbose mode if practical:

```bash
./gradlew obfuscate --info
```

or a plugin-specific option:

```kotlin
bozar {
    verbose.set(true)
}
```

Never log full source code or secrets.

## 3. Verification failure

If ASM verification fails:

- identify the first failing class
- identify the transformer sequence
- compare with pre-transform bytecode
- isolate the transformer
- test the smallest reproducer
- inspect stack frames and max stack/max locals

Do not blindly add stack/local padding as a permanent fix.

## 4. NoClassDefFoundError / ClassNotFoundException

Check:

- runtime-provided APIs are available in the verification classpath
- implementation dependencies are present
- libraries configured for Bozar are correct
- external classes were not renamed accidentally
- `META-INF/services` was preserved

## 5. Plugin fails to load

Inspect:

- `plugin.yml` or `paper-plugin.yml`
- `main` value
- output class path
- class visibility
- classloader errors
- renamed entrypoint

Compare:

```text
metadata main
      vs
actual class names in output JAR
```

## 6. Reflection failure

If runtime code calls `Class.forName`, reflective method lookup, or configuration-based class loading:

- identify the string/class reference
- add a keep rule
- determine whether metadata can be rewritten safely
- add a regression test

Do not assume bytecode analysis can discover every dynamic string.

## 7. Resource failure

If a plugin cannot find a resource:

- compare resource list before/after
- check duplicate names
- check path/case sensitivity
- inspect whether a transformer modified JAR output

## 8. Gradle incremental/cache problems

If a stale output appears:

- run with `--rerun-tasks`
- compare task inputs
- check whether all configuration properties are annotated as inputs
- inspect output declarations
- verify mapping seed/configuration is an input

Do not disable caching as the first response.

## 9. Reproducer policy

Every fixed bug that could regress should receive a minimal automated regression test.
