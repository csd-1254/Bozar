package io.github.vimasig.bozar.core;

import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for Bukkit/Paper metadata. The engine rewrites resources while
 * renaming, so these tests prove the two entrypoint strategies against a real JAR
 * instead of asserting an intended behavior.
 */
class MinecraftMetadataTest {

    private static final String PLUGIN = "com.example.HelloPlugin";
    private static final String PLUGIN_YML =
            "# My plugin\n"
            + "name: HelloPlugin\n"
            + "main: " + PLUGIN + "\n"
            + "version: 1.0.0\n"
            + "api-version: '1.20'\n"
            + "description: A test plugin\n";

    @TempDir
    Path dir;

    private Path jar;

    @BeforeEach
    void writePluginJar() throws Exception {
        jar = dir.resolve("plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("com/example/HelloPlugin.class"));
            out.write(classBytes("com/example/HelloPlugin"));
            out.closeEntry();

            out.putNextEntry(new JarEntry("com/example/Listener.class"));
            out.write(classBytes("com/example/Listener"));
            out.closeEntry();

            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(PLUGIN_YML.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();

            out.putNextEntry(new JarEntry("paper-plugin.yml"));
            out.write(("name: HelloPlugin\nmain: " + PLUGIN + "\napi-version: 1.20\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();

            out.putNextEntry(new JarEntry("config.yml"));
            out.write("setting: true\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();

            out.putNextEntry(new JarEntry("META-INF/services/org.bukkit.plugin.Plugin"));
            out.write(PLUGIN.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static byte[] classBytes(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitSource("src", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static BozarOptions rename(String exclude) {
        return new BozarOptions(
                BozarOptions.RenameOption.ALPHABET, BozarOptions.LineNumberOption.KEEP,
                BozarOptions.LocalVariableOption.KEEP, false, false, false,
                BozarOptions.ControlFlowObfuscationOption.OFF, false,
                BozarOptions.ConstantObfuscationOption.OFF, BozarOptions.StringObfuscationOption.OFF,
                new BozarOptions.WatermarkOptions(false, false, false, false, null, null, null, null));
    }

    @Test
    void detectsMetadataFiles() throws Exception {
        List<String> names = MinecraftMetadata.metadataNames(jar);
        assertEquals(List.of("plugin.yml", "paper-plugin.yml"), names);
        assertTrue(MinecraftMetadata.hasMetadata(jar));
    }

    @Test
    void detectsEntryPointsFromBothFiles() throws Exception {
        assertEquals(List.of(PLUGIN), MinecraftMetadata.entrypoints(jar));
    }

    @Test
    void serviceProvidersAreDiscovered() throws Exception {
        assertEquals(List.of(PLUGIN), MinecraftMetadata.serviceProviders(jar));
    }

    @Test
    void entrypointKeepModeLeavesMetadataUntouched() throws Exception {
        KeepRules rules = KeepRules.create().entrypoints(MinecraftMetadata.entrypoints(jar));
        Path out = dir.resolve("kept.jar");
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(jar).outputJar(out).exclude(String.join("\n", rules.lines())).options(rename("")).build());

        Map<String, String> entries = readEntries(out);
        assertEquals(PLUGIN, extractMain(entries.get("plugin.yml")),
                "keep mode must preserve the metadata entrypoint");
        assertEquals(PLUGIN, extractMain(entries.get("paper-plugin.yml")));

        Map<String, String> names = readNames(out);
        assertTrue(names.containsKey("com/example/HelloPlugin.class"),
                "the kept entrypoint class must keep its name");
        assertEquals(PLUGIN, entries.get("META-INF/services/org.bukkit.plugin.Plugin").trim(),
                "service provider declarations must keep the entrypoint name");
        assertEquals("setting: true\n", entries.get("config.yml"),
                "unrelated resources must be byte-identical");

        Path mapping = dir.resolve("keep-map.txt");
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(jar).outputJar(dir.resolve("keep.jar"))
                .exclude(String.join("\n", KeepRules.create().entrypoints(List.of(PLUGIN)).lines()))
                .options(rename("")).mappingOutput(mapping).build());
        String mapText = Files.readString(mapping);
        assertTrue(!mapText.contains("com/example/HelloPlugin"),
                "the kept entrypoint must not appear in the rename mapping, got:\n" + mapText);
        assertTrue(mapText.contains("class com/example/Listener"),
                "non-kept classes must still be renamed, got:\n" + mapText);
    }

    @Test
    void renameModeRewritesMetadataToPointAtTheRenamedClass() throws Exception {
        Path out = dir.resolve("rewritten.jar");
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(jar).outputJar(out).exclude("").options(rename("")).build());

        Map<String, String> entries = readEntries(out);
        String rewrittenMain = extractMain(entries.get("plugin.yml"));
        String rewrittenPaperMain = extractMain(entries.get("paper-plugin.yml"));

        assertNotEquals(PLUGIN, rewrittenMain, "rename mode must not leave the old name in metadata");
        assertNotEquals(PLUGIN, rewrittenPaperMain, "paper-plugin.yml must be rewritten too");
        assertEquals(rewrittenMain, rewrittenPaperMain, "both metadata files must agree");

        assertTrue(readNames(out).containsKey(rewrittenMain.replace('.', '/') + ".class"),
                "metadata must point at a class that actually exists in the output JAR");

        // The rename must also have touched the service provider declaration.
        assertEquals(rewrittenMain, entries.get("META-INF/services/org.bukkit.plugin.Plugin").trim(),
                "META-INF/services providers must be renamed alongside the class");

        // Non-metadata formatting is preserved around the changed line.
        assertTrue(entries.get("plugin.yml").startsWith("# My plugin"),
                "comment order in plugin.yml must be preserved");
        assertTrue(entries.get("plugin.yml").contains("version: 1.0.0"),
                "unrelated fields in plugin.yml must be preserved");
        assertTrue(entries.get("plugin.yml").contains("api-version: '1.20'"),
                "quoting in unrelated fields must be preserved");
    }

    private static String extractMain(String yaml) {
        for (String line : yaml.split("\\r?\\n", -1)) {
            if (line.startsWith("main:")) return line.substring("main:".length()).trim();
        }
        return null;
    }

    private static Map<String, String> readEntries(Path jar) throws Exception {
        Map<String, String> map = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory()) map.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static Map<String, String> readNames(Path jar) throws Exception {
        Map<String, String> map = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                map.put(entry.getName(), entry.getName());
            }
        }
        return map;
    }
}
