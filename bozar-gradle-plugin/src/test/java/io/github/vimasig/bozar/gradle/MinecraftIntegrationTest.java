package io.github.vimasig.bozar.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer C-2 — Gradle TestKit integration test against a synthetic Bukkit/Paper plugin.
 *
 * <p>{@link BozarPluginTest} uses a one-class "plugin", which cannot reach the failure modes a
 * real Minecraft plugin has: public API that must not be renamed, references to Bukkit API
 * classes that live on the server classpath, nested and inner classes, two metadata files that
 * both name the entrypoint, a service-provider file, and a resource inside a kept package. This
 * test builds a fixture with all of those and runs the real {@code obfuscate} task on it.
 *
 * <h2>Why the Bukkit API is a stub</h2>
 *
 * <p>Bukkit/Paper jars are published only to snapshot and developer repositories, not to Maven
 * Central, so compiling the fixture against the real API would add an unpinnable external
 * dependency to the test suite. Instead a {@code bukkit-stub} subproject compiles the small
 * {@code org.bukkit} surface the fixture uses into its own jar, and the fixture declares it as a
 * normal {@code implementation} dependency. That is also how Bukkit works at runtime: the API is
 * on the server classpath and is never inside the plugin jar.
 *
 * <h2>What this test proves</h2>
 *
 * <ul>
 *   <li>the real {@code obfuscate} task runs to success on a multi-class plugin whose code
 *       references types that are not in the input jar;</li>
 *   <li>the output jar opens, holds every input class under some name (nothing silently
 *       dropped), and passes the engine's ASM verification — the task would fail otherwise;</li>
 *   <li>{@code plugin.yml} and {@code paper-plugin.yml} survive; under {@code KEEP} both are
 *       byte-identical, under {@code REWRITE} both follow the renamed entrypoint;</li>
 *   <li>the entrypoint named by the metadata resolves to a class present in the jar under the
 *       configured entrypoint policy;</li>
 *   <li>no {@code org/bukkit/**} class is renamed, embedded, or otherwise touched — they are not
 *       in the input jar and not in the output jar;</li>
 *   <li>classes in a {@code keepPackage} package keep their names and their resources;</li>
 *   <li>application classes <em>are</em> renamed, and the recording of that rename appears in
 *       {@code build/bozar/mapping.txt};</li>
 *   <li>the output jar is loadable by a classloader that has the stub API on its parent path, so
 *       the metadata-named entrypoint can be linked and initialized.</li>
 * </ul>
 *
 * <h2>What this test does not prove</h2>
 *
 * <p><b>It is not a server boot test.</b> No Minecraft server is started. Nothing here proves
 * that Bukkit would accept the plugin, that {@code onEnable} runs, that events are dispatched,
 * that commands are registered, or that reflection performed by the server on class names it was
 * configured with at runtime finds anything. The Bukkit types loaded during the classloading
 * check are the stubs in this fixture, not real Bukkit, so a linking success here is evidence
 * about the fixture's API shape, not about the real server's. A green run therefore means the
 * artifact is <em>structurally</em> valid and metadata-consistent; it does not mean the plugin
 * works on a server. That gap is inherent to testing an obfuscator without a server, and it is
 * recorded in docs/12 rather than papered over.
 */
class MinecraftIntegrationTest {

    private static final String PLUGIN_YML = """
            # Plugin metadata; comments, ordering and blank lines must survive
            name: ExamplePlugin
            version: 1.0.0
            main: com.example.ExamplePlugin
            api-version: 1.21
            authors:
              - someone
              - someone-else

            commands:
              join:
                description: Joins the game
            """;

    private static final String PAPER_PLUGIN_YML = """
            name: ExamplePlugin
            version: 1.0.0
            main: com.example.ExamplePlugin
            api-version: '1.21'
            """;

    @TempDir
    Path projectDir;

    @Test
    void keepModeProducesAValidPluginJar() throws Exception {
        runFixture("KEEP");
    }

    @Test
    void rewriteModeKeepsMetadataConsistentWithTheRenamedEntrypoint() throws Exception {
        runFixture("REWRITE");
    }

    @Test
    void collectBozarDependenciesExportsThePluginDependency() throws Exception {
        writeFixture("KEEP");

        final BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(":plugin:collectBozarDependencies", "--console=plain", "--stacktrace")
                .build();

        final org.gradle.testkit.runner.BuildTask task = result.task(":plugin:collectBozarDependencies");
        assertNotNull(task, "the task must have run, output was:\n" + result.getOutput());
        assertEquals(TaskOutcome.SUCCESS, task.getOutcome(),
                "the collect task must succeed, output was:\n" + result.getOutput());

        // The plugin's only dependency is the bukkit-stub subproject; its jar must be exported.
        final Path depsDir = projectDir.resolve("plugin/build/bozar/dependencies");
        final List<Path> exported;
        try (var stream = Files.list(depsDir)) {
            exported = stream.filter(p -> p.toString().endsWith(".jar")).toList();
        }
        assertEquals(1, exported.size(),
                "exactly the stub jar must be exported to " + depsDir + ", found " + exported);
        assertTrue(Files.isRegularFile(exported.get(0)), "the exported file must be a jar");
    }

    /**
     * Builds the fixture, runs the real {@code obfuscate} task on it, and checks every
     * property docs/04 section 7 lists. See the class comment for what this does and does not
     * establish.
     */
    private void runFixture(String entrypointMode) throws Exception {
        writeFixture(entrypointMode);

        final BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("build", "--console=plain", "--stacktrace")
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":plugin:obfuscate").getOutcome(),
                "the obfuscate task must succeed, output was:\n" + result.getOutput());
        assertFalse(result.getOutput().contains("Invalid classes present"),
                "the engine must not report classes that failed verification:\n" + result.getOutput());

        final Path input = projectDir.resolve("plugin/build/libs/plugin.jar");
        final Path output = projectDir.resolve("plugin/build/libs/plugin-obfuscated.jar");
        assertTrue(Files.isRegularFile(input), "the input jar must exist at " + input);
        assertTrue(Files.isRegularFile(output), "the obfuscated jar must exist at " + output);
        assertFalse(Files.mismatch(input, output) == -1, "the output must not be a copy of the input");

        final Set<String> outputEntries = entryNames(output, n -> true);
        final Set<String> outputClasses = entryNames(output, n -> n.endsWith(".class"));

        // (1) Nothing was dropped. The mapping file says what each class became; every input
        // class must appear in the output under that name. Kept classes map to themselves.
        final Map<String, String> renameMap = readMapping(projectDir.resolve("plugin/build/bozar/mapping.txt"));
        final Set<String> inputClasses = entryNames(input, n -> n.endsWith(".class"));
        assertTrue(inputClasses.size() >= 9,
                "fixture drift: the input jar must hold the plugin, its nested classes, the two"
                        + " application classes, the API package and the service provider, found "
                        + inputClasses);
        for (String entry : inputClasses) {
            final String internal = entry.substring(0, entry.length() - ".class".length());
            final String expected = renameMap.getOrDefault(internal, internal) + ".class";
            assertTrue(outputEntries.contains(expected),
                    "class " + entry + " must appear in the output as " + expected
                            + "; the output held " + outputClasses + " and the mapping was " + renameMap);
        }

        // (2) Bukkit/Paper API types stay on the classpath: not renamed, not embedded. They were
        // never in the input jar, so if they are in the output the engine added them.
        assertEquals(List.of(), outputEntries.stream().filter(n -> n.startsWith("org/bukkit/")).toList(),
                "Bukkit API classes must never end up inside the plugin jar");

        // (3) A kept package keeps its classes and its resources, and service providers stay
        //     resolvable by name.
        for (String kept : List.of("com/example/api/Api.class", "com/example/api/ApiImpl.class",
                "com/example/api/PluginDescriptionFile.class", "com/example/api/plugin.yml")) {
            assertTrue(outputEntries.contains(kept), kept + " must survive unrenamed");
        }
        assertEquals("greeting=hello\nanswer=42\n",
                readEntry(output, "com/example/api/plugin.yml"),
                "a resource inside a kept package must be preserved verbatim");
        assertEquals("com.example.api.ApiImpl\n",
                readEntry(output, "META-INF/services/com.example.api.Api"),
                "the service provider must stay unrenamed so ServiceLoader still resolves it");

        // (4) Application classes are renamed, and the rename is recorded.
        for (String app : List.of("com/example/EventListener", "com/example/ExampleCommandExecutor")) {
            assertFalse(outputEntries.contains(app + ".class"),
                    app + " is application code and must be renamed; output held " + outputClasses);
            assertTrue(renameMap.containsKey(app),
                    "the rename of " + app + " must be recorded in the mapping file: " + renameMap);
        }

        // (5) Both metadata files survive and agree on the entrypoint.
        final String pluginYml = readEntry(output, "plugin.yml");
        final String paperYml = readEntry(output, "paper-plugin.yml");
        assertNotNull(pluginYml, "plugin.yml must survive in the output");
        assertNotNull(paperYml, "paper-plugin.yml must survive in the output");

        final String main = metadataMain(pluginYml, "plugin.yml");
        assertEquals(main, metadataMain(paperYml, "paper-plugin.yml"),
                "both metadata files must name the same entrypoint");

        if (entrypointMode.equals("KEEP")) {
            assertEquals(PLUGIN_YML, pluginYml, "KEEP must leave plugin.yml byte-identical");
            assertEquals(PAPER_PLUGIN_YML, paperYml, "KEEP must leave paper-plugin.yml byte-identical");
            assertEquals("com.example.ExamplePlugin", main, "KEEP must not rename the entrypoint");
        } else {
            assertFalse(pluginYml.contains("com.example.ExamplePlugin"),
                    "REWRITE must rewrite the entrypoint in plugin.yml, was:\n" + pluginYml);
            assertFalse(paperYml.contains("com.example.ExamplePlugin"),
                    "REWRITE must rewrite the entrypoint in paper-plugin.yml, was:\n" + paperYml);
            assertTrue(pluginYml.startsWith("# Plugin metadata; comments, ordering and blank lines must survive\n"),
                    "rewriting must not disturb the rest of the file, was:\n" + pluginYml);
            assertTrue(pluginYml.contains("  - someone-else\n") && pluginYml.contains("api-version: 1.21\n"),
                    "rewriting must preserve unrelated fields and their formatting, was:\n" + pluginYml);
        }

        // The entrypoint the metadata points at must be a real class in the jar.
        assertTrue(outputClasses.contains(main.replace('.', '/') + ".class"),
                "the entrypoint named by plugin.yml must exist in the jar: " + main
                        + "; the jar held " + outputClasses);

        // (6) Every written class passes an independent ASM structural check — the engine's own
        //     verification already ran inside the task, this re-reads the artifact from disk.
        for (String entry : outputClasses) {
            assertClassIsStructurallyValid(output, entry);
        }

        // (7) The entrypoint named by the metadata links and initializes against the API classes.
        assertEntrypointLoads(apiJar(), output, main);
    }

    // ------------------------------------------------------------------ fixture

    /**
     * Writes the two-subproject fixture. {@code bukkit-stub} supplies the API types the plugin
     * compiles against; {@code plugin} is the artifact under test.
     */
    private void writeFixture(String entrypointMode) throws Exception {
        write("settings.gradle", """
                rootProject.name = 'mifx'
                include 'bukkit-stub'
                include 'plugin'
                """);

        write("bukkit-stub/build.gradle", """
                plugins { id 'java' }
                group = 'org.bukkit'
                version = '1.21.0-stub'
                """);
        for (Map.Entry<String, String> stub : BUKKIT_STUBS.entrySet()) {
            write("bukkit-stub/src/main/java/" + stub.getKey(), stub.getValue());
        }

        write("plugin/build.gradle", PLUGIN_BUILD_GRADLE
                .replace("entrypointMode = 'KEEP'", "entrypointMode = '" + entrypointMode + "'"));

        write("plugin/src/main/java/com/example/ExamplePlugin.java", EXAMPLE_PLUGIN);
        write("plugin/src/main/java/com/example/EventListener.java", EVENT_LISTENER);
        write("plugin/src/main/java/com/example/ExampleCommandExecutor.java", EXAMPLE_COMMAND_EXECUTOR);
        write("plugin/src/main/java/com/example/api/Api.java", API);
        write("plugin/src/main/java/com/example/api/ApiImpl.java", API_IMPL);
        write("plugin/src/main/java/com/example/api/PluginDescriptionFile.java", API_PLUGIN_DESCRIPTION);

        write("plugin/src/main/resources/plugin.yml", PLUGIN_YML);
        write("plugin/src/main/resources/paper-plugin.yml", PAPER_PLUGIN_YML);
        write("plugin/src/main/resources/com/example/api/plugin.yml", "greeting=hello\nanswer=42\n");
        write("plugin/src/main/resources/META-INF/services/com.example.api.Api", "com.example.api.ApiImpl\n");
    }

    private void write(String relativePath, String content) throws Exception {
        final Path path = projectDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ fixture contents

    private static final String PLUGIN_BUILD_GRADLE = """
            plugins {
                id 'java'
                id 'io.github.vimasig.bozar'
            }

            dependencies {
                implementation project(':bukkit-stub')
            }

            bozar {
                profileName = 'BASIC'
                verify = true
                minecraft {
                    enabled = true
                }
                entrypointMode = 'KEEP'
                keep 'com.example.api.PluginDescriptionFile'
                keepPackage 'com.example.api'
                keepServiceProviders = true
                naming {
                    seed = 123456L
                }
            }
            """;

    private static final String EXAMPLE_PLUGIN = """
            package com.example;

            import com.example.api.Api;
            import com.example.api.ApiImpl;
            import com.example.api.PluginDescriptionFile;
            import org.bukkit.command.CommandExecutor;
            import org.bukkit.event.EventHandler;
            import org.bukkit.event.Listener;
            import org.bukkit.plugin.java.JavaPlugin;

            import java.util.ServiceLoader;

            /** The entrypoint named by plugin.yml and paper-plugin.yml. */
            public class ExamplePlugin extends JavaPlugin {

                /** The command handler declared in plugin.yml. */
                public static final class ExampleCommand implements CommandExecutor {
                    @Override
                    public boolean onCommand(String sender, String[] args) {
                        return args.length > 0;
                    }
                }

                /** A nested class the entrypoint owns; it must survive under some name. */
                static final class PluginInner {
                    private final ExamplePlugin owner;

                    PluginInner(ExamplePlugin owner) {
                        this.owner = owner;
                    }

                    String describe() {
                        return owner.getDescription().getName();
                    }
                }

                /** Exercises the API annotation, which the engine does not know about. */
                static final class Command implements CommandExecutor {
                    @EventHandler
                    void onEvent(Object event) {
                        if (event == null) throw new IllegalStateException("no event");
                    }

                    @Override
                    public boolean onCommand(String sender, String[] args) {
                        return false;
                    }
                }

                @Override
                public String getName() {
                    return "ExamplePlugin";
                }

                @Override
                public void onEnable() {
                    final PluginDescriptionFile description = new PluginDescriptionFile();
                    final Api api = ServiceLoader.load(Api.class).findFirst().orElseGet(ApiImpl::new);
                    final Listener listener = new EventListener(api);
                    final ExampleCommandExecutor executor = new ExampleCommandExecutor(api);
                    getLogger().info(description.getName() + " " + listener.getClass().getName()
                            + " " + executor.describe() + " " + new PluginInner(this).describe());
                    new Command().onCommand("console", new String[0]);
                }
            }
            """;

    private static final String EVENT_LISTENER = """
            package com.example;

            import com.example.api.Api;
            import org.bukkit.event.EventHandler;
            import org.bukkit.event.Listener;

            /** Application code: nothing keeps this, so it must be renamed. */
            public class EventListener implements Listener {
                private final Api api;

                public EventListener(Api api) {
                    this.api = api;
                }

                @EventHandler
                public void onEvent(Object event) {
                    api.log("event " + event);
                }
            }
            """;

    private static final String EXAMPLE_COMMAND_EXECUTOR = """
            package com.example;

            import com.example.api.Api;
            import org.bukkit.command.CommandExecutor;

            /** Application code implementing an external API interface. */
            public class ExampleCommandExecutor implements CommandExecutor {
                private final Api api;

                public ExampleCommandExecutor(Api api) {
                    this.api = api;
                }

                @Override
                public boolean onCommand(String sender, String[] args) {
                    api.log("command from " + sender);
                    return args.length > 0;
                }

                /** Renamed by the engine; only the caller that resolves it by name would break. */
                public String describe() {
                    return "executor:" + api.name();
                }
            }
            """;

    private static final String API = """
            package com.example.api;

            /** Public API of this plugin: other plugins compile against it. */
            public interface Api {
                void log(String message);

                String name();
            }
            """;

    private static final String API_IMPL = """
            package com.example.api;

            public class ApiImpl implements Api {
                private final String name = "impl";

                @Override
                public void log(String message) {
                }

                @Override
                public String name() {
                    return name;
                }
            }
            """;

    private static final String API_PLUGIN_DESCRIPTION = """
            package com.example.api;

            /** Kept by keepPackage; nothing in the fixture references it by name. */
            public class PluginDescriptionFile {
                private final String name = "ExamplePlugin";

                public String getName() {
                    return name;
                }
            }
            """;

    /**
     * The {@code org.bukkit} surface the fixture compiles against. Real Bukkit jars are not on
     * Maven Central, and the real API is a server-runtime artifact anyway, so the stub models the
     * only property that matters here: these types live outside the plugin jar.
     */
    private static Map<String, String> bukkitStubs() {
        final Map<String, String> stubs = new LinkedHashMap<>();

        stubs.put("org/bukkit/plugin/Plugin.java", """
                package org.bukkit.plugin;

                public interface Plugin {
                    String getName();
                }
                """);

        stubs.put("org/bukkit/plugin/java/JavaPlugin.java", """
                package org.bukkit.plugin.java;

                import org.bukkit.plugin.Logger;
                import org.bukkit.plugin.Plugin;
                import org.bukkit.plugin.PluginDescriptionFile;

                public abstract class JavaPlugin implements Plugin {
                    private final Logger logger = new Logger();

                    public Logger getLogger() {
                        return logger;
                    }

                    public PluginDescriptionFile getDescription() {
                        return new PluginDescriptionFile();
                    }

                    public void onEnable() {
                    }

                    public void onDisable() {
                    }
                }
                """);

        stubs.put("org/bukkit/plugin/Logger.java", """
                package org.bukkit.plugin;

                public class Logger {
                    public void info(String message) {
                    }

                    public void warning(String message) {
                    }
                }
                """);

        stubs.put("org/bukkit/plugin/PluginDescriptionFile.java", """
                package org.bukkit.plugin;

                public class PluginDescriptionFile {
                    public String getName() {
                        return "ExamplePlugin";
                    }

                    public String getVersion() {
                        return "1.0.0";
                    }
                }
                """);

        stubs.put("org/bukkit/event/Listener.java", """
                package org.bukkit.event;

                public interface Listener {
                }
                """);

        stubs.put("org/bukkit/event/EventHandler.java", """
                package org.bukkit.event;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface EventHandler {
                }
                """);

        stubs.put("org/bukkit/command/CommandExecutor.java", """
                package org.bukkit.command;

                public interface CommandExecutor {
                    boolean onCommand(String sender, String[] args);
                }
                """);

        return stubs;
    }

    private static final Map<String, String> BUKKIT_STUBS = bukkitStubs();

    // ------------------------------------------------------------------ readers and checks

    /** The stub jar: the fixture declares a version, so the archive name carries a suffix. */
    private Path apiJar() throws Exception {
        final Path libs = projectDir.resolve("bukkit-stub/build/libs");
        try (var stream = Files.list(libs)) {
            final List<Path> jars = stream.filter(p -> p.toString().endsWith(".jar")).toList();
            assertEquals(1, jars.size(), "exactly one jar must be built in " + libs + ", found " + jars);
            return jars.get(0);
        }
    }

    private static Map<String, String> readMapping(Path mappingFile) throws Exception {
        assertTrue(Files.isRegularFile(mappingFile), "the rename mapping must be written to " + mappingFile);
        final String content = Files.readString(mappingFile, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("# Bozar rename mapping\n"),
                "the mapping file must carry its header, was:\n" + content);

        final Map<String, String> classes = new HashMap<>();
        boolean sawField = false;
        boolean sawMethod = false;
        for (String line : content.split("\n")) {
            final String[] parts = line.split(" ");
            if (parts.length != 3) continue;
            switch (parts[0]) {
                case "class" -> classes.put(parts[1], parts[2]);
                case "field" -> sawField = true;
                case "method" -> sawMethod = true;
                default -> { }
            }
        }
        assertFalse(classes.isEmpty(), "the mapping file must record class renames:\n" + content);
        assertTrue(sawField, "the mapping file must record field renames:\n" + content);
        assertTrue(sawMethod, "the mapping file must record method renames:\n" + content);
        return classes;
    }

    private static String metadataMain(String yml, String label) {
        return yml.lines()
                .filter(line -> line.startsWith("main:"))
                .map(line -> line.substring("main:".length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no top-level main: in " + label + ":\n" + yml));
    }

    private static Set<String> entryNames(Path jar, Predicate<String> filter) throws Exception {
        final Set<String> names = new LinkedHashSet<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (filter.test(entry.getName())) names.add(entry.getName());
            }
        }
        return names;
    }

    private static byte[] readBytes(Path jar, String entryName) throws Exception {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) return in.readAllBytes();
            }
        }
        return null;
    }

    private static String readEntry(Path jar, String entryName) throws Exception {
        final byte[] bytes = readBytes(jar, entryName);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Re-reads one class from the written artifact and runs ASM's structural checker over it.
     * The engine verifies in memory during the task; this checks the bytes that actually landed
     * on disk, which is what a server would load.
     */
    private static void assertClassIsStructurallyValid(Path jar, String entry) throws Exception {
        final byte[] bytes = readBytes(jar, entry);
        assertNotNull(bytes, entry + " must be readable from " + jar);
        try {
            final ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            node.accept(new CheckClassAdapter(new ClassWriter(0), false));
        } catch (Throwable t) {
            throw new AssertionError(entry + " in " + jar + " is not structurally valid: " + t, t);
        }
    }

    /**
     * Loads the metadata-named entrypoint through a classloader whose parent holds the API, the
     * way a server would load it: API classes already present, plugin classes newly defined.
     */
    private static void assertEntrypointLoads(Path apiJar, Path output, String main) throws Exception {
        assertTrue(Files.isRegularFile(apiJar), "the stub API jar must exist at " + apiJar);

        // Both loaders are closed, otherwise the API jar stays locked and @TempDir cleanup fails.
        final URLClassLoader apiLoader = new URLClassLoader(
                new URL[]{apiJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        final URLClassLoader pluginLoader = new URLClassLoader(
                new URL[]{output.toUri().toURL()}, apiLoader);
        try {
            final Class<?> base = Class.forName("org.bukkit.plugin.java.JavaPlugin", false, apiLoader);

            final Class<?> entrypoint;
            try {
                entrypoint = Class.forName(main, true, pluginLoader);
            } catch (Throwable t) {
                throw new AssertionError("the entrypoint named by the metadata must load: " + main, t);
            }
            assertTrue(base.isAssignableFrom(entrypoint),
                    main + " must still be a " + base.getName());

            final Object instance;
            try {
                instance = entrypoint.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                throw new AssertionError(main + " must be instantiable by a plugin loader", t);
            }

            // getName() is declared by the API, so the engine must leave the override alone: a
            // rename here is precisely the defect that makes a plugin fail to load on a server.
            try {
                assertEquals("ExamplePlugin", base.getMethod("getName").invoke(instance),
                        "an override of an API method must not be renamed");
            } catch (Throwable t) {
                throw new AssertionError("the API method getName() must still work on " + main, t);
            }
        } finally {
            pluginLoader.close();
            apiLoader.close();
        }
    }
}
