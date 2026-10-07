# Final Acceptance Checklist

## Architecture

- [ ] No JavaFX dependency in the Gradle plugin runtime.
- [ ] No CLI is required for normal Gradle operation.
- [ ] Core obfuscation code has no Gradle dependency.
- [ ] Gradle plugin owns task/DSL concerns.

## Gradle

- [ ] Plugin applies through `plugins {}`.
- [ ] Dedicated obfuscation task exists.
- [ ] `build` integration is deterministic and documented.
- [ ] Inputs and outputs are declared correctly.
- [ ] No eager configuration-time file processing.
- [ ] Configuration cache behavior is tested or limitations documented.
- [ ] Build cache behavior is tested or disabled with justification.

## Obfuscation

- [ ] Existing Bozar transformers are preserved where supported.
- [ ] Transformer configuration is typed.
- [ ] Exclusion rules work.
- [ ] Mapping works if enabled.
- [ ] Verification runs after transformation.
- [ ] Invalid output cannot silently be accepted.

## Minecraft

- [ ] `plugin.yml` is preserved.
- [ ] `paper-plugin.yml` is preserved when present.
- [ ] Plugin entrypoint remains valid.
- [ ] Entry point rename/update mode is tested.
- [ ] Entry point keep mode is tested.
- [ ] Bukkit/Paper API classes are not accidentally transformed.
- [ ] Resources are preserved.
- [ ] Reflection limitations are documented.

## Testing

- [ ] Unit tests pass.
- [ ] Transformer tests pass.
- [ ] Gradle TestKit tests pass.
- [ ] Minecraft integration tests pass.
- [ ] Regression tests exist for fixed bugs.
- [ ] Clean checkout build passes.

## Quality

- [ ] Supported Java versions documented.
- [ ] Supported Gradle versions documented.
- [ ] ASM version documented.
- [ ] Upstream license preserved.
- [ ] Known limitations documented.
- [ ] Release artifact contains no accidental GUI/CLI dependencies.
