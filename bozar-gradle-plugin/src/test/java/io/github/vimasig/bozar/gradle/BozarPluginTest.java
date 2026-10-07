package io.github.vimasig.bozar.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer C — Gradle TestKit functional test for the plugin.
 *
 * <p>Boots a real Gradle build (not a simulated one) to verify that the plugin's task graph,
 * output location, and incremental / configuration-cache behavior actually work as
 * documented. Uses the project's own published plugin JAR so it exercises the real
 * {@code ObfuscateTask} and engine.
 */
class BozarPluginTest {

    @TempDir
    Path projectDir;

    private GradleRunner runner;

    @BeforeEach
    void setup() {
        runner = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("build", "--console=plain");
    }

    private void writeBuildFiles(String extraDsl) throws Exception {
        final Path root = projectDir;

        Files.writeString(root.resolve("settings.gradle"),
                "rootProject.name = 'bozartest'\n");

        // The plugin under test is on this test's own classpath; Gradle TestKit injects the
        // project's build classpath when the test module applies java-gradle-plugin.
        Files.writeString(root.resolve("build.gradle"),
                "plugins {\n" +
                        "    id 'java'\n" +
                        "    id 'io.github.vimasig.bozar'\n" +
                        "}\n" +
                        extraDsl);
    }

    /** Compiles a minimal single-class "plugin" into build/libs/bozartest.jar via the
     *  normal jar task, so the obfuscate task's input is a real file the engine can read.
     */
    private void writeSource() throws Exception {
        final Path srcDir = projectDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Hello.java"),
                "package com.example;\n" +
                        "public class Hello {\n" +
                        "    public String hello() {\n" +
                        "        return \"world\";\n" +
                        "    }\n" +
                        "}\n");
    }

    @Test
    void obfuscateTaskRunsAndProducesOutput() throws Exception {
        writeBuildFiles("");
        writeSource();

        BuildResult result = runner.build();
        assertEquals(TaskOutcome.SUCCESS,
                result.task(":obfuscate").getOutcome(),
                "obfuscate task must succeed");

        // Default output path: build/libs/<project>-obfuscated.jar
        final Path output = projectDir.resolve("build/libs/bozartest-obfuscated.jar");
        assertTrue(Files.isRegularFile(output),
                "output JAR must exist at " + output);
        assertFalse(output.equals(projectDir.resolve("build/libs/bozartest.jar")),
                "obfuscate must never write over the input JAR");

        // Mapping file at build/bozar/mapping.txt
        final Path mapping = projectDir.resolve("build/bozar/mapping.txt");
        assertTrue(Files.isRegularFile(mapping), "mapping file must be written");
    }

    @Test
    void obfuscateTaskFailsClearlyWhenInputMissing() throws Exception {
        // An explicitly-set input JAR that does not exist must fail with an actionable
        // message naming the property and the path, not a bare NPE or a silent no-op.
        writeBuildFiles(
                "bozar {\n" +
                        "    inputJar.set(layout.buildDirectory.file('does-not-exist.jar'))\n" +
                        "}\n");
        writeSource();

        BuildResult result = runner.buildAndFail();
        final String output = result.getOutput();
        assertTrue(output.contains("inputJar") && output.contains("does-not-exist.jar"),
                "failure message must name the property and the path, was: " + output);
    }

    @Test
    void rerunIsUpToDate() throws Exception {
        writeBuildFiles("");
        writeSource();

        runner.build();
        final BuildResult second = runner.build();
        assertEquals(TaskOutcome.UP_TO_DATE,
                second.task(":obfuscate").getOutcome(),
                "second run with no inputs changed must be UP-TO-DATE");
    }

    @Test
    void configurationCacheWorks() throws Exception {
        writeBuildFiles("");
        writeSource();

        // First run stores the configuration cache entry.
        runner.withArguments("obfuscate", "--configuration-cache", "--console=plain").build();

        // Second run must reuse it: look for the "reused" message.
        final BuildResult second = runner
                .withArguments("obfuscate", "--configuration-cache", "--console=plain")
                .build();
        final String output = second.getOutput();
        assertTrue(
                output.contains("Configuration cache entry reused")
                        || output.contains("Configuration cache entry was reused"),
                "configuration cache must be reusable across invocations, output was:\n"
                        + output);
    }

    @Test
    void minecraftKeepRulesPreserveEntrypoint() throws Exception {
        writeBuildFiles(
                "bozar {\n" +
                        "    minecraft {\n" +
                        "        enabled = true\n" +
                        "    }\n" +
                        "    keep('com.example.Hello')\n" +
                        "}\n");
        writeSource();

        // Inject metadata into the jar so the metadata reader has something to find.
        final Path srcDir = projectDir.resolve("src/main/resources");
        Files.createDirectories(srcDir);
        final String pluginYml = "# leading comment must survive\nname: Hello\n"
                + "main: com.example.Hello\nversion: 1.0\n";
        final String paperYml = "name: Hello\nmain: com.example.Hello\napi-version: '1.21'\n";
        Files.writeString(srcDir.resolve("plugin.yml"), pluginYml);
        Files.writeString(srcDir.resolve("paper-plugin.yml"), paperYml);

        final BuildResult result = runner.build();

        final Path output = projectDir.resolve("build/libs/bozartest-obfuscated.jar");
        assertTrue(Files.isRegularFile(output),
                "output JAR must exist at " + output + "\nbuild output:\n" + result.getOutput());

        // Both metadata files must survive byte-identically: under KEEP the class is not
        // renamed, so there is nothing to rewrite and nothing may be reformatted.
        assertEquals(pluginYml, readEntry(output, "plugin.yml"),
                "plugin.yml must be preserved verbatim in KEEP mode");
        assertEquals(paperYml, readEntry(output, "paper-plugin.yml"),
                "paper-plugin.yml must be preserved verbatim in KEEP mode");

        // The kept class must retain its original name in the output.
        assertTrue(zipEntryNames(output, n -> n.equals("com/example/Hello.class")).size() == 1,
                "com.example.Hello must be unrenamed; found "
                        + zipEntryNames(output, n -> n.endsWith(".class")));
    }

    @Test
    void minecraftRewriteModeRenamesEntrypointAndUpdatesMetadata() throws Exception {
        // REWRITE mode adds no keep rule for the entrypoint. The engine's ClassRenamerTransformer
        // rewrites dotted class names inside resources, so plugin.yml must follow the rename.
        writeBuildFiles(
                "bozar {\n" +
                        "    minecraft {\n" +
                        "        enabled = true\n" +
                        "    }\n" +
                        "    entrypointMode.set(io.github.vimasig.bozar.gradle.ObfuscateTask.EntrypointMode.REWRITE)\n" +
                        "}\n");
        writeSource();

        final Path resDir = projectDir.resolve("src/main/resources");
        Files.createDirectories(resDir);
        Files.writeString(resDir.resolve("plugin.yml"),
                "# leading comment must survive\nname: Hello\nmain: com.example.Hello\nversion: 1.0\n");

        runner.build();

        final Path output = projectDir.resolve("build/libs/bozartest-obfuscated.jar");
        assertTrue(Files.isRegularFile(output), "output JAR must exist at " + output);

        final String pluginYml = readEntry(output, "plugin.yml");
        assertNotNull(pluginYml, "plugin.yml must be preserved in the output");
        assertFalse(pluginYml.contains("com.example.Hello"),
                "plugin.yml main: must be rewritten to the new class name, was:\n" + pluginYml);
        assertTrue(pluginYml.contains("# leading comment must survive"),
                "unrelated metadata must be preserved verbatim, was:\n" + pluginYml);
        assertTrue(pluginYml.contains("name: Hello") && pluginYml.contains("version: 1.0"),
                "unrelated metadata fields must be preserved, was:\n" + pluginYml);

        // The renamed entrypoint named by plugin.yml must exist as a class in the output.
        final String main = pluginYml.lines()
                .filter(l -> l.startsWith("main:"))
                .map(l -> l.substring("main:".length()).trim())
                .findFirst().orElseThrow();
        final String mainEntry = main.replace('.', '/') + ".class";
        assertTrue(zipEntryNames(output, n -> n.equals(mainEntry)).size() == 1,
                "plugin.yml main: must point at a class present in the JAR: " + mainEntry
                        + "; entries were " + zipEntryNames(output, n -> n.endsWith(".class")));
    }

    @Test
    void buildCacheReusesTheObfuscatedJar() throws Exception {
        writeBuildFiles("");
        writeSource();

        // A local build cache inside the test project, so the test does not depend on
        // whatever cache the developer's ~/.gradle happens to hold.
        final Path cacheDir = projectDir.resolve("build-cache");
        final String[] cacheArgs = {"obfuscate", "--build-cache", "--console=plain"};
        Files.writeString(projectDir.resolve("settings.gradle"),
                "rootProject.name = 'bozartest'\n" +
                        "buildCache { local { directory = new File(rootDir, 'build-cache') } }\n");

        final BuildResult first = runner.withArguments(cacheArgs).build();
        assertEquals(TaskOutcome.SUCCESS, first.task(":obfuscate").getOutcome(),
                "first run must execute, was: " + first.getOutput());

        // Delete only this task's own outputs. Deleting the input jar too would force `:jar`
        // to re-execute, which is a different scenario (does the *dependency* re-run) and
        // would confound the result.
        deleteRecursively(projectDir.resolve("build/libs/bozartest-obfuscated.jar"));
        deleteRecursively(projectDir.resolve("build/bozar"));

        final BuildResult second = runner.withArguments(cacheArgs).build();
        assertEquals(TaskOutcome.FROM_CACHE, second.task(":obfuscate").getOutcome(),
                "a cacheable task must be restored from the build cache after its outputs are deleted. "
                        + "Second build output was:\n" + second.getOutput());
        assertTrue(Files.isRegularFile(projectDir.resolve("build/libs/bozartest-obfuscated.jar")),
                "the cached output must be restored, cache dir " + cacheDir);
    }

    @Test
    void disabledPluginSkipsObfuscationWithoutChangingTheGraph() throws Exception {
        writeBuildFiles("bozar { enabled = false }\n");
        writeSource();

        final BuildResult result = runner.build();
        assertEquals(TaskOutcome.SKIPPED, result.task(":obfuscate").getOutcome(),
                "a disabled plugin must skip the task rather than remove it from the graph");
        assertFalse(Files.exists(projectDir.resolve("build/libs/bozartest-obfuscated.jar")),
                "a skipped task must produce no output");
        // The plain jar the java plugin produced must be untouched.
        assertTrue(Files.isRegularFile(projectDir.resolve("build/libs/bozartest.jar")),
                "the unobfuscated jar must still be produced");
    }

    @Test
    void readmeDslConfiguresAndRuns() throws Exception {
        // The bozar { } block from README.md, line for line. Every property documented there
        // must exist on the extension: a user copy-pasting it must not hit
        // "Could not set unknown property". Keep this in sync with README.md.
        writeBuildFiles(
                "bozar {\n" +
                        "    profileName = 'BASIC'\n" +
                        "    enabled = true\n" +
                        "    verify = true\n" +
                        "    keep 'com.example.MyPlugin'\n" +
                        "    keepPackage 'com.example.api'\n" +
                        "    minecraft {\n" +
                        "        enabled = true\n" +
                        "    }\n" +
                        "    entrypointMode = 'KEEP'\n" +
                        "    keepServiceProviders = true\n" +
                        "    naming {\n" +
                        "        seed = 123456L\n" +
                        "    }\n" +
                        "    renamer     { enabled = true }\n" +
                        "    controlFlow { enabled = false }\n" +
                        "    constants   { enabled = false }\n" +
                        "    debug {\n" +
                        "        removeSourceFile = true\n" +
                        "        removeLineNumbers = true\n" +
                        "        removeLocalVariables = true\n" +
                        "    }\n" +
                        "}\n");
        writeSource();

        BuildResult result = runner.build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":obfuscate").getOutcome(),
                "the DSL documented in README.md must configure and run, output was:\n"
                        + result.getOutput());
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String readEntry(Path jar, String entryName) throws Exception {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        return null;
    }

    private static List<String> zipEntryNames(Path jar, java.util.function.Predicate<String> filter)
            throws Exception {
        final List<String> found = new java.util.ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (filter.test(entry.getName())) found.add(entry.getName());
            }
        }
        return found;
    }
}
