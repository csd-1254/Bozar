# Deviations from Upstream, and Known Limitations

This project reuses the upstream Bozar transformation engine unchanged wherever possible.
Everything listed here is either a deliberate difference from upstream, or a limitation that
was verified by reading the source rather than assumed.

Upstream source: `tmp/bozar` (Maven project, `io.github.vimasig.bozar`). This repository is
the Gradle-plugin successor.

## 1. Intentional deviations

### 1.1 GUI and CLI are removed from the execution model

Upstream's entry point is a JavaFX application that also exposes a CLI. Neither is part of
this artifact: `bozar-core` contains only `io.github.vimasig.bozar.core` (the facade plus
`KeepRules`, `MinecraftMetadata`, `Randoms`, `Profile`, `SignedJarStripper`) and
`io.github.vimasig.bozar.obfuscator` (the engine, transformers, and support utilities).
The Gradle plugin jar's runtime classpath is `bozar-core` plus ASM 9.9 — verified with
`./gradlew :bozar-gradle-plugin:dependencies --configuration runtimeClasspath`; there is no
JavaFX entry.

### 1.2 Swallowed `IOException` is converted into a typed failure

`Bozar.run()` catches `IOException` and calls `e.printStackTrace()` (upstream
`Bozar.java:248-250`), and its own verification call discards the boolean result, printing
only `err("Invalid classes present")` (`Bozar.java:233-234`).

The engine is **not modified** for this. `ObfuscationEngine` compensates outside `run()`:

- a missing output jar after the run is a hard `ObfuscationException`;
- the output is checked to contain every class the input had (see 1.6);
- verification is re-run via `BozarClassVerifier.verifyReport` and any reported failure is a
  hard `ObfuscationException` naming the class and its cause (see 1.7);
- any unchecked exception from the engine is wrapped in an `ObfuscationException` naming
  the offending input path.

Reason: docs/10 requires "Invalid output cannot silently be accepted."

### 1.3 JAR signature files are stripped from the output

New behavior, no upstream equivalent. Bytecode rewriting invalidates JAR signatures, and a
stale `META-INF/*.SF` makes the JVM reject the entire JAR with "Invalid signature file
digest for Manifest main attributes". `SignedJarStripper` removes `META-INF/*.SF`,
`.RSA`, `.DSA` and `.EC` (top-level `META-INF` only, case-insensitive). Re-signing is not
implemented. See docs/08 section 4.

### 1.4 Seeding the RNG

`ClassTransformer` had `protected final Random random = new Random();`. A new
`Randoms` holder (a `ThreadLocal<Long>`) replaces the inline initializer, and
`ObfuscationEngine` sets/clears the seed in a `try/finally` around `bozar.run()`. This is
the only change inside the transformer hierarchy; transformer logic is untouched.

Seedability was established by reading the sources, not assumed. Many upstream transformers
bypass the inherited `random` field and call `ThreadLocalRandom` directly, so the seed is
**not** a global reproducibility switch:

| Transformer | Honors the seed | Evidence |
| --- | --- | --- |
| `ConstantTransformer` | yes | `random.nextInt`/`nextLong` throughout |
| `StringTransformer` | yes | `random.nextInt(256)`, `random.nextInt(24)`, `random.nextInt()` |
| `LineNumberTransformer` (RANDOMIZE) | yes | `this.random.nextInt(Character.MAX_VALUE)` |
| `DummyClassTransformer` | yes | `random.nextInt(100)` |
| `HeavyControlFlowTransformer` | **partial** | mixes `random.nextLong()` with `ThreadLocalRandom.current()` in the same method body |
| `LightControlFlowTransformer` | **partial** | same mixture |
| `ShuffleTransformer` | **no** | calls `Collections.shuffle(classes)`, which uses `ThreadLocalRandom` |
| `CrasherTransformer` | **no** | `ThreadLocalRandom.current().nextInt(4)` |
| `ClassRenamerTransformer` (ALPHABET) | **no effect** | `StringUtils.getAlphabetCombinations()` is a fixed per-index table; identical input yields identical names with or without a seed |
| Renamers (`IlIlIlIlIl` mode) | **no** | `RenamerTransformer` builds names with `ThreadLocalRandom.current().nextBoolean()` |

Consequence for tests: a seed-reproducibility test must use an option set whose transformers
are in the "yes" column, **and** must not compare whole-file bytes, because ZIP entry
timestamps are wall-clock (see 2.6). `ObfuscationEngineTest#sameSeedReproducesTheSameOutput`
uses `ALPHABET` rename plus `LineNumberOption.KEEP`, control flow, shuffling and the crasher
all off — the largest reliable set — and compares entry names, sizes and payloads.

Consequence for users: `naming { seed = ... }` makes a **BASIC**-profile build
content-reproducible. A profile that enables control flow, shuffling or the crasher is not
content-reproducible even with a fixed seed, because upstream routes part of its randomness
through `ThreadLocalRandom`. Fixing that would mean editing transformer logic, which rule 5
of the brief forbids without a demonstrated reason. Neither case is byte-reproducible; see 2.6.

### 1.6 Dropped classes are a hard failure

`Bozar.run()` handles an unprocessable class by logging and continuing rather than failing:
`classNode.accept(checkClassAdapter)` throws for a class whose code cannot be parsed, and
the `catch (Throwable)` at `Bozar.java:187-193` calls `err(...)` and `continue`s, so that
class is simply absent from the output. `Bozar.java:201-208` does the same on a write
failure.

The engine is **not modified** for this. `ObfuscationEngine.checkNoClassesWereDropped`
computes, for each input class, the output entry name the run should produce — applying the
engine's own class rename table — and fails if any expected name is absent from the output.

Names rather than counts. Renaming changes a class's entry name, but the change is recorded
in `ClassRenamerTransformer` and is a deterministic function of the input name, so an input
class has one expected output entry. A count comparison expresses only a lower bound, and the
dummy-class and crasher watermarks each add one synthetic class, so on any profile with
watermarks enabled a dropped input class can be offset by an added one and the counts come
out even. Measured before this changed to name tracking: an input of two classes, one
unparseable, with the dummy-class watermark enabled, produced a verified output of two
entries — the renamed survivor plus the dummy class — and no failure, with the dropped class
absent. `DropMaskingTest` covers that case; it fails against the count comparison.

The comparison is a containment (`expected ⊆ actual`), not an equality, so the synthetic
watermark classes and any other additions stay permitted. Nothing else in the engine removes
a non-excluded input class by design, so any shortfall is a swallowed failure.

This check exists because verification cannot catch it: once a class is missing it is absent
from the list the verifier walks, so verification passes on a truncated JAR and prints
"JAR verified successfully!". Measured before the fix: four of five malformed-class fixtures
produced an output with zero class entries and a successful run.

### 1.7 Verification failures carry the class name and cause

Upstream's `Bozar.run()` discards the verification boolean and prints only
`err("Invalid classes present")` (`Bozar.java:233-234`), and `BozarClassVerifier` reports
the offending class and the ASM failure to `Bozar.err` — straight to `System.err` — so
neither reaches a caller.

`BozarClassVerifier` gained a `verifyReport` entry point returning a `VerificationReport`
(one entry per offending class: `<class>: <root cause>`), and `verify` is now a thin
wrapper over it so existing callers are unaffected. The facade turns a non-empty report
into an `ObfuscationException` naming the output JAR, the stage, and the failing classes.

This is the one upstream file this project edits beyond the seeding change in 1.4.

### 1.8 Renamer key shapes in the mapping file

`ObfuscationEngine.writeMapping` emits three sorted, labeled blocks straight from the
renamers' own maps. The shapes are upstream's, not chosen here:

```text
class com/example/Hello com/example/A
field com/example/Hello.myField a
method com.example.Hello.run() a
```

Note the inconsistency: class and field keys are internal names (`/`), method keys are
dotted with a descriptor. Converting class keys to dotted would be lossy for method
descriptors, so the raw form is preserved.

## 2. Verified limitations

### 2.1 Metadata is read textually, not parsed as YAML

`MinecraftMetadata` reads `plugin.yml` / `paper-plugin.yml` with a line scan. This is
deliberate: it keeps unrelated fields, comments, ordering and blank lines byte-identical,
which is what the resource-preservation guarantee requires.

Consequences:

- Only a **top-level** `main:` key is found. An indented `main:` (a nested mapping) is not.
- Anchors, aliases, and folded/block scalars (`main: >-\n  com.example.Foo`) are not
  supported. A folded entrypoint is not kept, and under `REWRITE` the rewrite is
  textual substitution, so it would also not be rewritten.
- A trailing inline comment is stripped at the first whitespace or `#`.

If a project uses folded scalars for `main:`, set `entrypointMode = KEEP` and add an
explicit `keep 'com.example.MyPlugin'` rule.

### 2.2 Reflection cannot be detected

As docs/04 section 4 states, arbitrary reflection, string-built class names, and
configuration-defined class names cannot be detected reliably. The plugin provides explicit
keep rules (`keep`, `keepPackage`, `keepPatterns`) and keeps service providers from
`META-INF/services/*`; it does not attempt reflection analysis and makes no claim that it
does.

### 2.3 Iteration order of the transformer pipeline is upstream's

`TransformManager.getTransformers()` registers 17 transformers and runs them in its own
order. This project does not reorder them; doing so would change output for every existing
user of the engine.

### 2.4 Build-cache correctness depends on declared inputs

`ObfuscateTask` declares profile, every toggle, the seed, keep patterns, the input jar, and
the classpath. It does **not** know about files the engine might read outside those
(explicitly supplied libraries are covered by `getClasspath()`). Adding an undeclared
side-channel input would make the task cache-incorrect; the tests
(`buildCacheReusesTheObfuscatedJar`) cover only the declared set.

### 2.5 Minecraft API classes are not transformed

By construction, not by policy: the engine transforms only classes present in the input
jar. Bukkit/Paper API classes live on the dependency classpath and never enter the
transformer. The task also passes the main source set's runtime classpath to the engine as
resolution libraries.

### 2.6 Output is not byte-reproducible

`Bozar.run()` writes every entry with `new JarEntry(name)`, which stamps the current wall
clock. So the same input, seed and options produce different bytes on two consecutive runs
whenever they cross a second boundary — measured: entry times advanced
`1791365108000 → 110000 → 112000` across three runs, and the files differed at equal size.

Two consequences:

- A seed controls the **content** of a build (class names, line numbers, constants), not its
  bytes. docs/08 section 5 asks for "no timestamps that unnecessarily change output" and says
  to document why if byte-for-byte reproducibility is not possible. This is that: the
  timestamps are upstream's, and normalizing them would mean editing the engine's write loop.
  The one place the project *does* preserve times is `SignedJarStripper.copyEntry`, which
  copies `entry.getTime()` along with the stored metadata.
- Any test that compares whole-file bytes is flaky. It will pass while both runs land in the
  same second and fail after, with no code change. Compare entry names, sizes and payloads
  instead — that is what content reproducibility means, and it is what the seed actually
  guarantees.

### 2.7 Class-file versions are bounded by ASM, not by this project

ASM 9.9 accepts class-file major versions up to 70 and rejects 71 with
`IllegalArgumentException("Unsupported class file major version 71")`. Measured, the engine
round-trips major 52 through 70 (Java 8 through Java 26) with the version byte preserved
unchanged, and fails on 71 (Java 27) — so the practical ceiling is Java 26, not Java 21 as
the README stated before this phase.

The facade wraps that rejection in an `ObfuscationException` naming the input, so the
unsupported-version case fails loudly rather than as a bare ASM exception. Raising the
ceiling means upgrading ASM, which this project does not do: the version is pinned to
upstream's so that transformer behavior stays comparable.

## 3. License

The repository `LICENSE` is MIT, `Copyright (c) 2020-present vimasig (Vedat Sazoglu)`,
matching the upstream project's own copyright line.

**Flagged, not resolved:** the upstream checkout at `tmp/bozar` contains **no LICENSE,
COPYING, or NOTICE file**, and its `pom.xml` declares **no `<licenses>` block**. The MIT
text in this repository's root is therefore the project's own statement of the upstream
license, not a copy of an upstream license file. Anyone redistributing this artifact should
confirm the upstream licensing terms with the upstream author before publishing. This is
recorded rather than silently assumed.

Phase 8 added a `<licenses>` block to both published POMs (`bozar-core`,
`bozar-gradle-plugin`) so downstream license scanners see MIT rather than "no license
declared". That declares this repository's statement only; it does not resolve the upstream
question above. The plugin marker POM is auto-generated from the `gradlePlugin {}` block and
carries no license metadata.
