# Agent Rules

These rules are mandatory for implementation agents.

1. Read all documentation before coding.
2. Work phase-by-phase.
3. Do not skip tests because a change appears small.
4. Do not rewrite a transformer until its existing implementation has been inspected.
5. Do not silently change default obfuscation behavior.
6. Do not make Minecraft compatibility claims without an integration test.
7. Do not hide exceptions or convert failures into warnings merely to make builds pass.
8. Prefer the smallest architectural change that produces a clean Gradle-native design.
9. Keep core logic independent of Gradle.
10. Use Gradle lazy configuration APIs.
11. Keep output deterministic where the design permits it.
12. Every runtime bug fixed must get a regression test.
13. Before finalizing, run the complete relevant test suite and inspect the generated JAR.
14. Update documentation when behavior changes.
15. If an assumption cannot be verified from source or official documentation, stop and investigate rather than guessing.
