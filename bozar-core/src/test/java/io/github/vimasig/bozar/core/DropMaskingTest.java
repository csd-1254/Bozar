package io.github.vimasig.bozar.core;

import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A dropped class must be caught even when the run also adds synthetic classes.
 *
 * <p>The check used to compare entry counts, which is only a lower bound: the dummy-class and
 * crasher watermarks each add one class, so on any profile with watermarks enabled a dropped
 * input class can be offset by an added one and the count comes out even. Measured before the
 * fix, the case in {@link #aDropIsNotMaskedWhenWatermarksAddAClass} produced a verified output
 * of two entries and no failure, with the dropped class absent.
 */
class DropMaskingTest {

    @TempDir
    Path dir;

    private static byte[] validClass(String internalName) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(org.objectweb.asm.Opcodes.V21,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor method = writer.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn("hello " + internalName);
        method.visitInsn(org.objectweb.asm.Opcodes.POP);
        method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A method that pops with nothing on the stack: unparseable, so the engine drops it. */
    private static byte[] droppedClass(String internalName) {
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(
                org.objectweb.asm.ClassWriter.COMPUTE_FRAMES | org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(org.objectweb.asm.Opcodes.V21,
                org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor method = writer.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "run", "()F", null, null);
        method.visitCode();
        method.visitInsn(org.objectweb.asm.Opcodes.POP);
        method.visitInsn(org.objectweb.asm.Opcodes.FRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private Path inputWithOneDroppableClass() throws Exception {
        Path in = dir.resolve("two-class.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(in))) {
            out.putNextEntry(new JarEntry("com/example/Good.class"));
            out.write(validClass("com/example/Good"));
            out.closeEntry();
            out.putNextEntry(new JarEntry("com/example/Bad.class"));
            out.write(droppedClass("com/example/Bad"));
            out.closeEntry();
        }
        return in;
    }

    private static BozarOptions renamingWithDummyClassWatermark() {
        return new BozarOptions(
                BozarOptions.RenameOption.ALPHABET,
                BozarOptions.LineNumberOption.KEEP,
                BozarOptions.LocalVariableOption.KEEP,
                false, false, false,
                BozarOptions.ControlFlowObfuscationOption.OFF,
                false,
                BozarOptions.ConstantObfuscationOption.OFF,
                BozarOptions.StringObfuscationOption.OFF,
                new BozarOptions.WatermarkOptions(true, false, false, false,
                        "Obfuscated with Bozar", null, null, null));
    }

    @Test
    void aDropIsNotMaskedWhenWatermarksAddAClass() throws Exception {
        Path input = inputWithOneDroppableClass();
        Path output = dir.resolve("two-class-out.jar");

        ObfuscationEngine.ObfuscationException e =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ObfuscationEngine.ObfuscationException.class,
                        () -> ObfuscationEngine.execute(ObfuscationRequest.builder()
                                .inputJar(input).outputJar(output)
                                .options(renamingWithDummyClassWatermark()).build()));

        assertTrue(e.getMessage().contains(output.getFileName().toString()),
                "the failure must name the output JAR that is unusable; was: " + e.getMessage());
        assertTrue(e.getMessage().contains("dropped"),
                "the failure must say the classes were dropped; was: " + e.getMessage());

        // The output really is missing the droppable class: renaming moved the surviving one,
        // the watermark added one, so the counts are equal and only name tracking catches this.
        Set<String> entries = classEntries(output);
        assertEquals(2, entries.size(),
                "one renamed class plus the dummy-class watermark; was: " + entries);
        assertTrue(entries.stream().noneMatch(name -> name.endsWith("Bad.class")),
                "the dropped class must be absent from the output; was: " + entries);
        assertTrue(entries.contains("Obfuscated with Bozar.class"),
                "the dummy-class watermark must be present, which is what masked the drop; was: "
                        + entries);
    }

    @Test
    void aRenamedAndWatermarkedJarWithoutDropsPasses() throws Exception {
        // Guards the fix against being stricter than the engine: the watermark additions must
        // stay permitted, and renaming must not look like a drop.
        Path input = dir.resolve("one-class.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("com/example/Good.class"));
            out.write(validClass("com/example/Good"));
            out.closeEntry();
        }

        Path output = dir.resolve("one-class-out.jar");
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input).outputJar(output)
                .options(renamingWithDummyClassWatermark()).build());

        Set<String> entries = classEntries(output);
        assertEquals(2, entries.size(),
                "the renamed class plus the watermark must both survive; was: " + entries);
        assertTrue(entries.contains("Obfuscated with Bozar.class"),
                "the watermark must be present; was: " + entries);
    }

    private static Set<String> classEntries(Path jar) throws Exception {
        Set<String> names = new TreeSet<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().endsWith(".class")) names.add(entry.getName());
            }
        }
        return names;
    }
}
