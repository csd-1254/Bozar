package io.github.vimasig.bozar.core;

import io.github.vimasig.bozar.obfuscator.Bozar;
import io.github.vimasig.bozar.obfuscator.utils.BozarClassVerifier;
import io.github.vimasig.bozar.obfuscator.utils.BozarClassVerifier.VerificationReport;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assertions;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 hardening: documented class-file version support, a large-JAR scale run,
 * stable entry ordering, and the failure-diagnostics contract of docs/03.
 *
 * <p>Each test was added after establishing the behavior by measurement rather than by
 * reading the README, because the documented ceiling was wrong.
 */
class HardeningTest {

    @TempDir
    Path dir;

    private Path input;

    @BeforeEach
    void writeInputJar() throws Exception {
        input = dir.resolve("input.jar");
        writeClass(input, "com/example/Hello", Opcodes.V21);
    }

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

    private static BozarOptions alphabet() {
        return new BozarOptions(
                BozarOptions.RenameOption.ALPHABET,
                BozarOptions.LineNumberOption.KEEP,
                BozarOptions.LocalVariableOption.KEEP,
                false, false, false,
                BozarOptions.ControlFlowObfuscationOption.OFF,
                false,
                BozarOptions.ConstantObfuscationOption.OFF,
                BozarOptions.StringObfuscationOption.OFF,
                new BozarOptions.WatermarkOptions(false, false, false, false, null, null, null, null));
    }

    /** A minimal valid class carrying the requested class-file major version. */
    private static byte[] classBytes(String internalName, int majorVersion) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(majorVersion, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
                "java/lang/Object", null);
        writer.visitSource(internalName + ".java", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn("Hello, " + internalName);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void writeClass(Path jar, String internalName, int majorVersion) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(internalName.replace('.', '/') + ".class"));
            out.write(classBytes(internalName, majorVersion));
            out.closeEntry();
        }
    }

    private static int majorVersion(Path jar) throws Exception {
        byte[] bytes = readClass(jar);
        return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
    }

    private static byte[] readClass(Path jar) throws Exception {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().endsWith(".class")) return in.readAllBytes();
            }
        }
        throw new AssertionError("no class entry in " + jar);
    }

    private static int classCount(Path jar) throws Exception {
        int count = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().endsWith(".class")) count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ versions

    @Test
    void roundTripsClassFileVersionJava8ThroughJava26() throws Exception {
        // Measured, not assumed: ASM 9.9 accepts major 70 and rejects 71, so the supported
        // range is wider than the "up to Java 21" line the README used to claim.
        int[] majors = {Opcodes.V1_8, Opcodes.V17, Opcodes.V21, Opcodes.V22, Opcodes.V25, Opcodes.V26};
        for (int major : majors) {
            Path in = dir.resolve("in" + major + ".jar");
            Path out = dir.resolve("out" + major + ".jar");
            writeClass(in, "com/example/V", major);

            ObfuscationEngine.execute(ObfuscationRequest.builder()
                    .inputJar(in).outputJar(out).options(off()).build());

            assertEquals(major, majorVersion(out),
                    "the class-file version must round-trip unchanged for major " + major);
            assertEquals(1, classCount(out), "major " + major + " must produce one class entry");
        }
    }

    @Test
    void rejectsAClassFileVersionBeyondASMWithATypedError() throws Exception {
        // Java 27. ASM 9.9 throws IllegalArgumentException, which the facade must wrap so
        // the failure stays in one type and names the input.
        Path in = dir.resolve("future.jar");
        writeClass(in, "com/example/V", Opcodes.V26 + 1);

        ObfuscationEngine.ObfuscationException e = Assertions.assertThrows(
                ObfuscationEngine.ObfuscationException.class,
                () -> ObfuscationEngine.execute(ObfuscationRequest.builder()
                        .inputJar(in).outputJar(dir.resolve("future-out.jar")).options(off()).build()));
        assertTrue(e.getMessage().contains("future.jar"),
                "the failure must name the offending input; was: " + e.getMessage());
        assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("unsupported class file"),
                "the failure must say why; was: " + e.getMessage());
    }

    // ------------------------------------------------------------- failure paths

    @Test
    void verificationReportIdentifiesTheFailingClassAndItsCause() throws Exception {
        // One stack slot holding a non-throwable reference, then ATHROW: the frame is valid
        // enough to parse but CheckClassAdapter's data-flow analysis rejects it. This is the
        // fixture that makes verifyReport report rather than throw.
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/Bad", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "boom", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn("not a throwable");
        method.visitInsn(Opcodes.ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();

        Path jar = dir.resolve("bad.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("com/example/Bad.class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }

        Bozar bozar = new Bozar(new BozarConfig("in.jar", "out.jar", "", List.of(), off()));
        VerificationReport report = BozarClassVerifier.verifyReport(
                bozar, jar, BozarClassVerifier.class.getClassLoader());

        assertFalse(report.isOk(), "an ATHROW of a non-throwable must not verify");
        assertEquals(1, report.getFailures().size(), "exactly one class must be reported");
        String failure = report.getFailures().get(0);
        assertTrue(failure.startsWith("com/example/Bad:"),
                "the failing class name must be reported; was: " + failure);
        assertFalse(failure.substring(failure.indexOf(':') + 1).isBlank(),
                "the cause must be reported, not just the class name; was: " + failure);

        // The upstream entry point must still behave the same way as before this change.
        assertEquals(false, BozarClassVerifier.verify(bozar, jar,
                BozarClassVerifier.class.getClassLoader()));
    }

    @Test
    void failsWhenTheEngineSilentlyDropsAClass() throws Exception {
        // The engine cannot write this class, but Bozar.run() catches the Throwable, logs it
        // and continues, so it vanishes from the output. Verification of the surviving
        // classes then reports success, and the truncated JAR would have been published.
        // Only the entry-count comparison catches it.
        Path broken = dir.resolve("broken.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(broken))) {
            out.putNextEntry(new JarEntry("com/example/Bad.class"));
            out.write(dropFixture());
            out.closeEntry();
        }

        Path output = dir.resolve("broken-out.jar");
        ObfuscationEngine.ObfuscationException e = Assertions.assertThrows(
                ObfuscationEngine.ObfuscationException.class,
                () -> ObfuscationEngine.execute(ObfuscationRequest.builder()
                        .inputJar(broken).outputJar(output).options(off()).build()));
        assertTrue(e.getMessage().contains(output.getFileName().toString()),
                "the failure must name the output JAR that is unusable; was: " + e.getMessage());
        assertTrue(e.getMessage().contains("dropped"),
                "the failure must say the classes were dropped, not just that the build failed; was: "
                        + e.getMessage());
    }

    /** A method that pops with nothing on the stack: unparseable, so the engine drops it. */
    private static byte[] dropFixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "com/example/Bad", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()F", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.FRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void aValidClassStillPassesTheDroppedClassCheck() throws Exception {
        // Guards against the invariant being stricter than the engine: an unchanged, valid
        // input must survive with no shortfall.
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input).outputJar(dir.resolve("ok.jar")).options(off()).build());
        assertEquals(classCount(input), classCount(dir.resolve("ok.jar")));
    }

    // ------------------------------------------------------------------- scale

    @Test
    void processesATwoThousandClassJarAndRenamesEveryClassUniquely() throws Exception {
        // 2000 classes is about three seconds. The alphabet renamer has 143312 distinct names
        // (52 two-letter plus 52 three-letter), so this size exercises the rename table without
        // approaching the limit.
        int count = 2000;
        Path large = dir.resolve("large.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(large))) {
            for (int i = 0; i < count; i++) {
                String name = "com/example/C" + i;
                out.putNextEntry(new JarEntry(name.replace('.', '/') + ".class"));
                out.write(classBytes(name, Opcodes.V21));
                out.closeEntry();
            }
        }

        Path output = dir.resolve("large-out.jar");
        Path mapping = dir.resolve("large.map");
        ObfuscationResult result = ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(large).outputJar(output).options(alphabet()).mappingOutput(mapping).build());

        assertTrue(result.getOutputSize() > 0, "a large JAR must produce a non-empty output");
        assertEquals(count, classCount(output),
                "renaming must not lose or duplicate a single class");

        Set<String> renamed = new HashSet<>();
        for (String line : Files.readAllLines(mapping)) {
            if (line.startsWith("class ")) {
                String[] parts = line.split(" ");
                assertEquals(3, parts.length, "every mapping line must be typed: " + line);
                renamed.add(parts[2]);
            }
        }
        assertEquals(count, renamed.size(),
                "every class must get a distinct renamed name, which is where an exhausted "
                        + "alphabet would first show up");
    }

    // --------------------------------------------------------- determinism

    @Test
    void writesEntriesInStableOrderAcrossRuns() throws Exception {
        // Byte-for-byte equality is covered elsewhere; this pins the weaker and more fragile
        // part separately, because a reordered ZIP changes bytes without changing content.
        Path outA = dir.resolve("ordA.jar");
        Path outB = dir.resolve("ordB.jar");
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input).outputJar(outA).options(off()).build());
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input).outputJar(outB).options(off()).build());

        assertEquals(entryNames(outA), entryNames(outB), "entry order must be stable");
    }

    private static List<String> entryNames(Path jar) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) names.add(entry.getName());
        }
        return names;
    }
}
