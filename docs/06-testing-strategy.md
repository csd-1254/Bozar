# Testing Strategy

## 1. Test layers

Use four layers.

### Layer A — Core unit tests

Test:

- name generation
- exclusion matching
- transformer configuration
- metadata parsing/updating
- resource copying
- mapping generation
- deterministic output
- invalid input handling

### Layer B — Transformer tests

Each transformer should have focused bytecode fixtures.

For every transformer test:

1. Create a small Java fixture.
2. Compile it.
3. Transform it.
4. Verify the resulting class with ASM.
5. Load/execute it where practical.
6. Assert the intended transformation.

### Layer C — Gradle TestKit functional tests

Create temporary Gradle projects and run:

```bash
./gradlew obfuscate
```

and:

```bash
./gradlew build
```

Verify:

- plugin applies correctly
- task graph is correct
- output exists
- task is incremental where intended
- configuration cache works if claimed
- build cache works if claimed
- useful Gradle logging is produced

### Layer D — Minecraft integration tests

Build synthetic plugin JARs and run metadata/classloading checks.

## 2. Mandatory regression cases

At minimum include:

- ordinary plugin
- plugin with `plugin.yml`
- plugin with `paper-plugin.yml`
- nested classes
- anonymous classes
- lambdas
- reflection
- service loader resource
- excluded package
- excluded class
- entrypoint rename/update
- entrypoint keep mode
- invalid/corrupt JAR
- empty JAR
- missing dependency classpath
- Java bytecode versions supported by the project
- duplicate resource names
- signed JAR metadata

## 3. Output verification

For every successful obfuscation:

- Open the output as a ZIP/JAR.
- Verify expected class count.
- Verify expected resource count where relevant.
- Run ASM verification.
- Check metadata.
- Load representative classes.

## 4. Failure tests

The plugin must fail clearly for:

- missing input
- input that is not a JAR
- invalid bytecode
- invalid configuration
- output path conflicts
- unsupported class version when applicable

Error messages should include actionable context.
