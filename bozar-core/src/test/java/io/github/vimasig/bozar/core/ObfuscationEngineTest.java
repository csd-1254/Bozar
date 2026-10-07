package io.github.vimasig.bozar.core;

import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks of the engine facade against real JARs. These are the tests that
 * would have caught upstream's silent-failure behaviour: a swallowed {@link java.io.IOException}
 * and a verification warning that does not stop publication.
 */
class ObfuscationEngineTest {

    @TempDir
    Path dir;

    private Path input;

    @BeforeEach
    void writeInputJar() throws Exception {
        input = dir.resolve("input.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("com/example/Hello.class"));
            out.write(classBytes("com/example/Hello", "Hello"));
            out.closeEntry();

            // A non-class resource that must survive the run byte-for-byte.
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write("name: Hello\nmain: com.example.Hello\n".getBytes());
            out.closeEntry();
        }
    }

    private static byte[] classBytes(String internalName, String visibleName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        method.visitCode();
        // A real line number: LineNumberTransformer.RANDOMIZE has nothing to mutate without one.
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        method.visitLineNumber(1, start);
        method.visitLdcInsn("Hello, " + visibleName);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Every transformer disabled: proves transport, resource copy and verification in isolation. */
    private static BozarOptions off() {
        return new BozarOptions(
                BozarOptions.RenameOption.OFF,
                BozarOptions.LineNumberOption.KEEP,
                BozarOptions.LocalVariableOption.KEEP,
                false, false, false,
                BozarOptions.ControlFlowObfuscationOption.OFF,
                false,
                BozarOptions.ConstantObfuscationOption.OFF,
                BozarOptions.StringObfuscationOption.OFF,
                new BozarOptions.WatermarkOptions(false, false, false, false, null, null, null, null));
    }

    @Test
    void runsAndPreservesResources() throws Exception {
        Path output = dir.resolve("out.jar");
        ObfuscationResult result = ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input).outputJar(output).options(off()).build());

        assertTrue(Files.isRegularFile(output));
        assertEquals(result.getOutputJar(), output);
        assertTrue(result.getOutputSize() > 0);

        try (var jar = new java.util.jar.JarInputStream(Files.newInputStream(output))) {
            java.util.jar.JarEntry entry = jar.getNextJarEntry();
            while (entry != null) {
                if (entry.getName().equals("plugin.yml")) {
                    byte[] bytes = jar.readAllBytes();
                    assertEquals("name: Hello\nmain: com.example.Hello\n", new String(bytes));
                    break;
                }
                entry = jar.getNextJarEntry();
            }
        }
    }

    @Test
    void neverOverwritesTheInputJar() throws Exception {
        long before = Files.size(input);
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input).outputJar(input).options(off()).build());
        assertEquals(before, Files.size(input), "input JAR must not be mutated in place");
    }

    @Test
    void missingInputFailsBeforeRunningTheEngine() {
        assertThrows(ObfuscationEngine.ObfuscationException.class,
                () -> ObfuscationEngine.execute(ObfuscationRequest.builder()
                        .inputJar(dir.resolve("nope.jar"))
                        .outputJar(dir.resolve("nope-out.jar"))
                        .options(off())
                        .build()));
    }

    @Test
    void requestValidationRejectsMissingFields() {
        assertThrows(IllegalStateException.class, () -> ObfuscationRequest.builder()
                .outputJar(dir.resolve("o.jar")).options(off()).build());
        assertThrows(IllegalStateException.class, () -> ObfuscationRequest.builder()
                .inputJar(input).options(off()).build());
        assertThrows(IllegalStateException.class, () -> ObfuscationRequest.builder()
                .inputJar(input).outputJar(dir.resolve("o.jar")).build());
    }

    @Test
    void corruptInputFailsWithATypedError() throws Exception {
        // Not a ZIP at all. The engine reports this as a raw unchecked exception; the facade
        // must convert it to a typed failure that names the offending input.
        Path notAJar = dir.resolve("broken.jar");
        Files.writeString(notAJar, "this is definitely not a zip archive");

        ObfuscationEngine.ObfuscationException e = assertThrows(
                ObfuscationEngine.ObfuscationException.class,
                () -> ObfuscationEngine.execute(ObfuscationRequest.builder()
                        .inputJar(notAJar).outputJar(dir.resolve("broken-out.jar")).options(off()).build()));
        assertTrue(e.getMessage().contains("broken.jar"),
                "the failure must name the offending input; was: " + e.getMessage());
    }

    @Test
    void emptyJarFailsWithATypedError() throws Exception {
        Path empty = dir.resolve("empty.jar");
        try (java.util.jar.JarOutputStream out =
                     new java.util.jar.JarOutputStream(Files.newOutputStream(empty))) {
            // A valid ZIP with no entries: the engine's "does not look like a proper JAR" case.
        }

        ObfuscationEngine.ObfuscationException e = assertThrows(
                ObfuscationEngine.ObfuscationException.class,
                () -> ObfuscationEngine.execute(ObfuscationRequest.builder()
                        .inputJar(empty).outputJar(dir.resolve("empty-out.jar")).options(off()).build()));
        assertTrue(e.getMessage().contains("empty.jar"),
                "the failure must name the offending input; was: " + e.getMessage());
    }

    @Test
    void signingFilesAreRemovedFromTheOutput() throws Exception {
        // A JAR carrying stale signature files must come out unsigned: a stale .SF makes the
        // JVM reject the whole JAR with "Invalid signature file digest".
        Path signed = dir.resolve("signed.jar");
        try (java.util.jar.JarOutputStream out =
                     new java.util.jar.JarOutputStream(Files.newOutputStream(signed))) {
            out.putNextEntry(new java.util.jar.JarEntry("com/example/Hello.class"));
            out.write(classBytes("com/example/Hello", "Hello"));
            out.closeEntry();
            out.putNextEntry(new java.util.jar.JarEntry("META-INF/PLUGIN.SF"));
            out.write("Signature-Version: 1.0\n".getBytes());
            out.closeEntry();
            out.putNextEntry(new java.util.jar.JarEntry("META-INF/PLUGIN.RSA"));
            out.write(new byte[]{0, 1, 2, 3});
            out.closeEntry();
        }

        Path output = dir.resolve("signed-out.jar");
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(signed).outputJar(output).options(off()).build());

        try (java.util.jar.JarInputStream jar = new java.util.jar.JarInputStream(Files.newInputStream(output))) {
            java.util.jar.JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                assertFalse(entry.getName().toUpperCase(java.util.Locale.ROOT).endsWith(".SF")
                                || entry.getName().toUpperCase(java.util.Locale.ROOT).endsWith(".RSA"),
                        "signature file must not survive into the output: " + entry.getName());
            }
        }
    }

    @Test
    void writesAStableSortedMappingFile() throws Exception {
        Path mapping = dir.resolve("mapping.txt");
        ObfuscationResult result = ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input)
                .outputJar(dir.resolve("out.jar"))
                .options(new BozarOptions(
                        BozarOptions.RenameOption.ALPHABET,
                        BozarOptions.LineNumberOption.KEEP,
                        BozarOptions.LocalVariableOption.KEEP,
                        false, false, false,
                        BozarOptions.ControlFlowObfuscationOption.OFF,
                        false,
                        BozarOptions.ConstantObfuscationOption.OFF,
                        BozarOptions.StringObfuscationOption.OFF,
                        new BozarOptions.WatermarkOptions(false, false, false, false, null, null, null, null)))
                .mappingOutput(mapping)
                .build());

        assertEquals(mapping, result.getMappingOutput());
        assertTrue(Files.isRegularFile(mapping), "mapping file must exist when renaming is on");
        String content = Files.readString(mapping);
        assertFalse(content.isEmpty(), "mapping must not be empty when renaming is enabled");
        assertTrue(content.contains("class com/example/Hello "),
                "mapping must contain the renamed class; got:\n" + content);
        assertTrue(content.contains("method com/example/Hello.run()V "),
                "mapping must contain renamed methods (internal-name + real descriptor, per SimpleRemapper); got:\n" + content);
        assertTrue(content.lines().allMatch(
                        line -> line.isBlank() || line.startsWith("#")
                                || line.matches("class \\S+ \\S+") || line.matches("field \\S+ \\S+")
                                || line.matches("method \\S+ \\S+")),
                "every non-comment line must be a typed rename entry; got:\n" + content);
    }

    @Test
    void sameSeedReproducesTheSameOutput() throws Exception {
        // The seed controls content, not timestamps: the engine writes entries with wall-clock
        // times, so two runs are never byte-identical unless they land in the same second.
        // Comparing bytes here made this test flaky; compare names, sizes and payloads instead.
        BozarOptions options = new BozarOptions(
                BozarOptions.RenameOption.ALPHABET, BozarOptions.LineNumberOption.KEEP,
                BozarOptions.LocalVariableOption.KEEP, false, false, false,
                BozarOptions.ControlFlowObfuscationOption.OFF, false,
                BozarOptions.ConstantObfuscationOption.OFF, BozarOptions.StringObfuscationOption.OFF,
                new BozarOptions.WatermarkOptions(false, false, false, false, null, null, null, null));

        Path outA = dir.resolve("a.jar");
        Path outB = dir.resolve("b.jar");
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input).outputJar(outA).options(options).seed(123456L).build());
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input).outputJar(outB).options(options).seed(123456L).build());

        assertEquals(entryContents(outA), entryContents(outB),
                "identical seeds must produce the same entries with the same payloads");
    }

    /** Entry name, size and payload, ignoring the timestamp the ZIP stores for each. */
    private static List<String> entryContents(Path jar) throws Exception {
        List<String> entries = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(jar))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.add(entry.getName() + " " + entry.getSize() + " "
                        + java.util.HexFormat.of().formatHex(in.readAllBytes()));
            }
        }
        return entries;
    }

    @Test
    void differentSeedsProduceDifferentRenamedClasses() throws Exception {
        // ALPHABET + RANDOMIZE line numbers is a deterministic function of the RNG seed
        // (ALPHABET names come from a fixed per-index table but their allocation order is
        // seed-driven; RANDOMIZE line numbers call this.random.nextInt directly), so two
        // different seeds must differ somewhere in the output.
        BozarOptions options = new BozarOptions(
                BozarOptions.RenameOption.ALPHABET, BozarOptions.LineNumberOption.RANDOMIZE,
                BozarOptions.LocalVariableOption.KEEP, false, false, false,
                BozarOptions.ControlFlowObfuscationOption.OFF, false,
                BozarOptions.ConstantObfuscationOption.OFF, BozarOptions.StringObfuscationOption.OFF,
                new BozarOptions.WatermarkOptions(false, false, false, false, null, null, null, null));

        Path outA = dir.resolve("a2.jar");
        Path outB = dir.resolve("b2.jar");
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input).outputJar(outA).options(options).seed(1L).build());
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input).outputJar(outB).options(options).seed(2L).build());

        byte[] a = Files.readAllBytes(outA);
        byte[] b = Files.readAllBytes(outB);
        assertFalse(java.util.Arrays.equals(a, b),
                "different seeds must change at least one line number or class name");
    }
}
