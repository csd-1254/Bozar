# Implementation Phases

## Phase 0 — Inventory

- Read the complete Bozar source tree.
- Enumerate all transformers.
- Identify GUI-only, CLI-only, and core code.
- Identify configuration model.
- Identify resource handling.
- Identify verifier behavior.
- Record dependencies.

Acceptance: an inventory document or code comments clearly identify reusable core and code to remove.

## Phase 1 — Gradle project foundation

- Convert build to Gradle.
- Configure Java toolchain.
- Add `java-gradle-plugin`.
- Add plugin marker metadata.
- Establish tests.

Acceptance: plugin artifact builds and can be applied in a TestKit sample project.

## Phase 2 — Core extraction

- Extract reusable engine.
- Remove direct GUI/CLI assumptions.
- Introduce `ObfuscationRequest` / result model if needed.
- Preserve transformer behavior.

Acceptance: core tests can invoke obfuscation without Gradle or JavaFX.

## Phase 3 — Gradle extension and task

- Implement typed DSL.
- Implement `ObfuscateTask`.
- Wire input/output.
- Wire `jar -> obfuscate`.
- Add logging.

Acceptance: `./gradlew obfuscate` produces a valid transformed JAR.

## Phase 4 — Minecraft integration

- Add metadata detection.
- Add entrypoint keep/update behavior.
- Add Minecraft keep rules.
- Preserve resources.

Acceptance: synthetic Bukkit/Paper plugin integration tests pass.

## Phase 5 — Transformer compatibility

- Port/configure all supported transformers.
- Test each individually.
- Test supported combinations.

Acceptance: every advertised feature has an automated regression test or is explicitly marked unsupported.

## Phase 6 — Gradle correctness

- Add incremental input/output declarations.
- Test configuration cache.
- Test build cache if enabled.
- Test repeated builds.

Acceptance: no stale outputs or incorrect cache hits.

## Phase 7 — Hardening

- Invalid JAR tests.
- Java version tests.
- Large JAR tests.
- Failure diagnostics.
- Reproducibility checks.
- Documentation.

Acceptance: complete test suite passes and every known limitation is documented.

## Phase 8 — Release readiness

- Verify plugin marker.
- Verify POM metadata.
- Verify license.
- Verify README.
- Verify versioning.
- Verify publication artifact.
- Verify clean checkout build.

Acceptance: a clean environment can build and consume the plugin without undocumented manual steps.
