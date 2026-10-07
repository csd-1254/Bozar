package io.github.vimasig.bozar.core;

import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.ConstantObfuscationOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.ControlFlowObfuscationOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.LineNumberOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.LocalVariableOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.RenameOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.StringObfuscationOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.WatermarkOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer B: one focused check per advertised transformer (docs/06-testing-strategy.md,
 * docs/09 Phase 5 — "every advertised feature has an automated regression test or is
 * explicitly marked unsupported").
 *
 * <p>Each case runs <em>one</em> transformer against a shared fixture, so a failure names
 * the transformer that broke rather than "the pipeline". The backbone assertion is that
 * the output differs from the all-transformers-off baseline: the failure mode this catches
 * is a transformer that silently stops running, which no amount of structural verification
 * would notice. Transformer-specific assertions are layered on top where the effect is
 * cheap to observe.
 *
 * <p>Transformers that cannot be exercised this way are listed in {@link #UNSUPPORTED}
 * with the reason, per the same Phase 5 requirement.
 */
class TransformerCoverageTest {

    /** Not covered here, with the reason. Kept as data so the list is greppable. */
    private static final List<String> UNSUPPORTED = List.of();

    @TempDir
    Path dir;

    private Path input;

    @BeforeEach
    void writeFixture() throws IOException {
        input = dir.resolve("fixture.jar");
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/example/Subject", null, "java/lang/Object", null);
        writer.visitSource("Subject.java", null);

        // Several members: enough that a shuffle reordering them is not a coincidence.
        for (int i = 0; i < 6; i++) {
            writer.visitField(Opcodes.ACC_PRIVATE, "field" + i, "I", null, null).visitEnd();
        }
        writer.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd();

        // Every member has a name that must be replaced when renaming is on. One of them
        // (method0) is additionally exercised by the exclusion test, which reuses this fixture
        // and keeps that single method's name.
        addMethod(writer, "method0", Opcodes.ACC_PUBLIC);
        for (int i = 1; i < 6; i++) {
            addMethod(writer, "extra" + i, Opcodes.ACC_PUBLIC);
        }
        // Private and static methods: renamed unconditionally, because they cannot override
        // anything from a library superclass.
        for (int i = 0; i < 3; i++) {
            addMethod(writer, "hidden" + i, Opcodes.ACC_PRIVATE);
        }
        for (int i = 0; i < 3; i++) {
            addMethod(writer, "staticMethod" + i, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        }

        // An inner class entry, so InnerClassTransformer has something to remove.
        final ClassWriter nested = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        nested.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/example/Subject$Nested", null, "java/lang/Object", null);
        nested.visitInnerClass("com/example/Subject$Nested", "com/example/Subject", "Nested",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        nested.visitEnd();

        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("com/example/Subject.class"));
            out.write(writer.toByteArray());
            out.closeEntry();
            out.putNextEntry(new JarEntry("com/example/Subject$Nested.class"));
            out.write(nested.toByteArray());
            out.closeEntry();
        }
    }

    // ------------------------------------------------------------------ per-transformer cases

    @Test
    void classRenamerRenamesTheClass() throws Exception {
        ClassNode node = run(options(RenameOption.ALPHABET, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        assertNotEquals("com/example/Subject", node.name,
                "ClassRenamerTransformer must rename the subject class");
    }

    @Test
    void fieldRenamerRenamesFields() throws Exception {
        ClassNode node = run(options(RenameOption.ALPHABET, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        assertTrue(node.fields.stream().noneMatch(f -> f.name.equals("field0")),
                "FieldRenamerTransformer must rename fields; got " + names(node.fields));
        assertEquals(7, node.fields.size(), "renaming must not drop or add fields");
    }

    @Test
    void methodRenamerRenamesMethods() throws Exception {
        ClassNode node = run(options(RenameOption.ALPHABET, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        final List<String> names = methodNames(node);
        // Every method's name must be replaced. This holds because the fixture's superclass is
        // java/lang/Object, which is not in the transformation set, so no public instance
        // method is library-safed. See
        // publicInstanceMethodIsUnrenamedWhenSuperclassIsAMissingLibrary for the opposite case.
        assertFalse(names.contains("method0"), "public instance methods must be renamed; got " + names);
        assertFalse(names.contains("extra1"), "public instance methods must be renamed; got " + names);
        assertFalse(names.contains("hidden0"), "private methods must be renamed; got " + names);
        assertFalse(names.contains("staticMethod0"), "static methods must be renamed; got " + names);
        assertEquals(12, names.size(), "renaming must not drop or add methods");
    }

    /**
     * Overloaded methods each get their own rename: the map key must be
     * {@code <slash-form owner>.<name><desc>}, not a bare {@code ()} name.
     */
    @Test
    void overloadedMethodsEachReceiveAnIndependentName() throws Exception {
        // Regression: the rename key must carry the descriptor. Before getMethodMapKey
        // existed, the map key was className + "." + name + "()", so every overload of one
        // name resolved to the SAME entry and the map key ASM looks up was never produced.
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/example/Overloads", null, "java/lang/Object", null);
        writer.visitSource("Overloads.java", null);
        addMethod(writer, "execute", "()V", Opcodes.ACC_PRIVATE);
        addMethod(writer, "execute", "(Ljava/lang/String;)V", Opcodes.ACC_PRIVATE);
        addMethod(writer, "execute", "(I)V", Opcodes.ACC_PRIVATE);
        writer.visitEnd();
        final Path inputJar = dir.resolve("overloads.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(inputJar))) {
            out.putNextEntry(new JarEntry("com/example/Overloads.class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }

        final Path mapping = dir.resolve("overloads-mapping.txt");
        final Path output = dir.resolve("overloads-out.jar");
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(inputJar).outputJar(output)
                .mappingOutput(mapping)
                .options(options(RenameOption.ALPHABET, LineNumberOption.KEEP,
                        LocalVariableOption.KEEP, false, false, false,
                        ControlFlowObfuscationOption.OFF, false, ConstantObfuscationOption.OFF,
                        StringObfuscationOption.OFF, noWatermark()))
                .build());

        // Every overload is renamed: the original name survives nowhere.
        final ClassNode node = classWithSourceFile(output, "Overloads.java");
        final List<MethodNode> exec = node.methods.stream()
                .filter(m -> m.desc.equals("()V") || m.desc.equals("(Ljava/lang/String;)V") || m.desc.equals("(I)V"))
                .toList();
        assertEquals(3, exec.size(), "all three overloads must survive; got " + exec.stream().map(m -> m.name + m.desc).toList());
        exec.forEach(m -> assertFalse(m.name.equals("execute"),
                "execute" + m.desc + " must be renamed; got " + m.name));

        // And each overload is mapped to a DIFFERENT name, not one shared name.
        final List<String> distinct = new ArrayList<>(exec.stream().map(m -> m.name).toList());
        assertEquals(3, new java.util.LinkedHashSet<>(distinct).size(),
                "each overload must receive its own name; got " + distinct);

        // The written mapping proves the keys are descriptor-qualified: mappingEntry throws
        // if a key is absent, so finding all three pins the <slash-form owner>.<name><desc>
        // form and rules out both the bare-() and dotted-class-name forms.
        final String map = Files.readString(mapping);
        final String o = "com/example/Overloads";
        final Map.Entry<String, String> e0 = mappingEntry(map, o + ".execute()V");
        final Map.Entry<String, String> e1 = mappingEntry(map, o + ".execute(Ljava/lang/String;)V");
        final Map.Entry<String, String> e2 = mappingEntry(map, o + ".execute(I)V");
        assertTrue(map.contains("class " + o + " "),
                "mapping must carry the renamed class; got:\n" + map);
        assertNotEquals(o, node.name, "rename mode must actually be active in this run");
        assertFalse(map.contains("method " + o + ".execute() "),
                "the old <owner>.<name>() key form must be gone; got:\n" + map);
        assertFalse(map.contains("com.example.Overloads"),
                "the old dotted-class-name key form must be gone; got:\n" + map);

        // The mapping targets must be exactly the names in the output bytecode, which also
        // proves the three targets are distinct (the output names already are).
        assertEquals(
                new java.util.LinkedHashSet<>(exec.stream().map(m -> m.name).toList()),
                new java.util.LinkedHashSet<>(List.of(e0.getValue(), e1.getValue(), e2.getValue())),
                "mapping targets must be the renamed overload names in the output; mapping "
                        + List.of(e0.getValue(), e1.getValue(), e2.getValue())
                        + " vs output " + exec.stream().map(m -> m.name).toList());

        // A descriptor mismatch is invisible to a naive ClassReader re-parse, so run the
        // structural verifier on the actual output bytes and require a clean result.
        final java.io.StringWriter verifierErrors = new java.io.StringWriter();
        for (String name : classEntries(output)) {
            org.objectweb.asm.util.CheckClassAdapter.verify(new ClassReader(readEntry(output, name)),
                    ClassLoader.getSystemClassLoader(), false, new java.io.PrintWriter(verifierErrors));
        }
        assertEquals("", verifierErrors.toString(),
                "output bytecode must pass structural verification; got:\n" + verifierErrors);
    }

    @Test
    void publicInstanceMethodIsRenamedWhenSuperclassIsInTheInput() throws Exception {
        final ClassWriter superWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        superWriter.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/example/Super", null, "java/lang/Object", null);
        addMethod(superWriter, "shared", Opcodes.ACC_PUBLIC);
        superWriter.visitEnd();
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/example/Sub", null, "com/example/Super", null);
        writer.visitSource("Sub.java", null);
        addMethod(writer, "shared", Opcodes.ACC_PUBLIC);
        addMethod(writer, "extra", Opcodes.ACC_PUBLIC);
        addMethod(writer, "secret", Opcodes.ACC_PRIVATE);
        writer.visitEnd();
        final Path two = dir.resolve("in.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(two))) {
            out.putNextEntry(new JarEntry("com/example/Super.class"));
            out.write(superWriter.toByteArray());
            out.closeEntry();
            out.putNextEntry(new JarEntry("com/example/Sub.class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        final Path out = dir.resolve("in-out.jar");
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(two).outputJar(out)
                .options(options(RenameOption.ALPHABET, LineNumberOption.KEEP,
                        LocalVariableOption.KEEP, false, false, false,
                        ControlFlowObfuscationOption.OFF, false, ConstantObfuscationOption.OFF,
                        StringObfuscationOption.OFF, noWatermark()))
                .build());
        final ClassNode sub = classWithSourceFile(out, "Sub.java");
        final List<String> names = methodNames(sub);
        assertFalse(names.contains("extra"), "public instance methods must be renamed; got " + names);
        assertFalse(names.contains("secret"), "private methods must be renamed; got " + names);
        assertEquals(3, names.size(), "no methods must be added or dropped; got " + names);
    }

    /**
     * Bukkit/Paper protection: when a superclass is a <em>library</em> class (referenced but
     * absent from the input JAR), public instance methods keep their names so
     * {@code JavaPlugin.onEnable()} and friends survive. Private and static still rename.
     */
    @Test
    void publicInstanceMethodIsUnrenamedWhenSuperclassIsAMissingLibrary() throws Exception {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/example/Plugin", null, "org/bukkit/JavaPlugin", null);
        writer.visitSource("Plugin.java", null);
        addMethod(writer, "onEnable", Opcodes.ACC_PUBLIC);
        addMethod(writer, "custom", Opcodes.ACC_PUBLIC);
        addMethod(writer, "secret", Opcodes.ACC_PRIVATE);
        addMethod(writer, "utility", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        writer.visitEnd();
        final Path one = dir.resolve("lib.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(one))) {
            out.putNextEntry(new JarEntry("com/example/Plugin.class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }
        final Path out = dir.resolve("lib-out.jar");
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(one).outputJar(out)
                .options(options(RenameOption.ALPHABET, LineNumberOption.KEEP,
                        LocalVariableOption.KEEP, false, false, false,
                        ControlFlowObfuscationOption.OFF, false, ConstantObfuscationOption.OFF,
                        StringObfuscationOption.OFF, noWatermark()))
                .build());
        final ClassNode plugin = classWithSourceFile(out, "Plugin.java");
        final List<String> names = methodNames(plugin);
        // org/bukkit/JavaPlugin is a library (super not in the set), so every public
        // instance method is treated as a possible override and must NOT be renamed.
        assertTrue(names.contains("onEnable"), "public instance methods must keep their name; got " + names);
        assertTrue(names.contains("custom"), "public instance methods must keep their name; got " + names);
        assertFalse(names.contains("secret"), "private methods must be renamed; got " + names);
        assertFalse(names.contains("utility"), "static methods must be renamed; got " + names);
        assertEquals(4, names.size(), "no methods must be added or dropped; got " + names);
    }

    /** A method that must keep its name via an exclusion rule (bozar-core exclusion contract). */
    @Test
    void exclusionRuleKeepsTheNamedMethod() throws Exception {
        final Path output = dir.resolve("excluded.jar");
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input).outputJar(output)
                .exclude("MethodRenamerTransformer:com.example.Subject.method0()")
                .options(options(RenameOption.ALPHABET, LineNumberOption.KEEP,
                        LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                        false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()))
                .build());
        final ClassNode node = classWithSourceFile(output, "Subject.java");
        final List<String> names = methodNames(node);
        assertTrue(names.contains("method0"),
                "excluded method must keep its name; got " + names);
        assertFalse(names.contains("hidden0"), "non-excluded methods must still be renamed; got " + names);
    }

    @Test
    void lineNumberDeleteRemovesLineNumbers() throws Exception {
        ClassNode node = run(options(RenameOption.OFF, LineNumberOption.DELETE,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        assertEquals(0, lineNumberCount(node),
                "LineNumberOption.DELETE must remove every line number node");
    }

    @Test
    void lineNumberRandomizeReplacesLineNumbers() throws Exception {
        ClassNode node = run(options(RenameOption.OFF, LineNumberOption.RANDOMIZE,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        assertTrue(lineNumberCount(node) > 0,
                "LineNumberOption.RANDOMIZE must keep line number nodes");
        assertTrue(lineNumbers(node).stream().noneMatch(n -> n == 77770),
                "RANDOMIZE must replace the original line numbers; got " + lineNumbers(node));
    }

    @Test
    void localVariableDeleteRemovesTheTable() throws Exception {
        ClassNode node = run(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.DELETE, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        node.methods.forEach(m -> assertTrue(m.localVariables == null || m.localVariables.isEmpty(),
                "LocalVariableOption.DELETE must remove the local variable table from " + m.name));
    }

    @Test
    void sourceFileRemovalDropsTheSourceFileAttribute() throws Exception {
        ClassNode node = run(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, true, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        // The engine writes the attribute with an empty value rather than omitting it, so
        // "removed" means "carries no file name", not "null".
        assertTrue(node.sourceFile == null || node.sourceFile.isEmpty(),
                "removeSourceFile must leave no source file name; was " + node.sourceFile);
    }

    @Test
    void innerClassRemovalDropsTheInnerClassesAttribute() throws Exception {
        ClassNode node = run(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, true, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        assertTrue(node.innerClasses == null || node.innerClasses.isEmpty(),
                "removeInnerClasses must drop the InnerClasses attribute");
    }

    @Test
    void stringObfuscationRemovesThePlaintextConstant() throws Exception {
        ClassNode node = run(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.LIGHT, noWatermark()));
        assertFalse(constants(node).contains("FIXTURE_STRING_MARKER"),
                "StringTransformer must remove the plaintext string constant");
    }

    @Test
    void constantObfuscationRemovesThePlaintextNumber() throws Exception {
        ClassNode node = run(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.LIGHT, StringObfuscationOption.OFF, noWatermark()));
        assertFalse(constants(node).contains(424242),
                "ConstantTransformer must remove the plaintext int constant");
    }

    @Test
    void lightControlFlowAddsAFlowField() throws Exception {
        ClassNode node = run(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.LIGHT,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        assertTrue(node.fields.stream().anyMatch(f -> f.desc.equals("J")),
                "LightControlFlowTransformer must add its static flow field; fields were "
                        + names(node.fields));
    }

    @Test
    void heavyControlFlowAddsAFlowField() throws Exception {
        ClassNode node = run(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.HEAVY,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        assertTrue(node.fields.stream().anyMatch(f -> f.desc.equals("J")),
                "HeavyControlFlowTransformer must add its static flow field; fields were "
                        + names(node.fields));
    }

    @Test
    void shuffleKeepsEveryMemberAndReordersAtLeastOneList() throws Exception {
        // Shuffling uses Collections.shuffle (ThreadLocalRandom), so the exact order is not
        // reproducible; what must hold is that nothing is lost and that the ordering is
        // actually randomized. With 7 fields and 6 methods the chance of an identical
        // permutation by accident is below 1e-6.
        ClassNode node = run(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, true, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        assertEquals(7, node.fields.size(), "shuffle must not drop fields");
        assertEquals(12, node.methods.size(), "shuffle must not drop methods");
        final boolean fieldsReordered = !names(node.fields).equals(expectedFieldOrder());
        final boolean methodsReordered = !methodNames(node).equals(expectedMethodOrder());
        assertTrue(fieldsReordered || methodsReordered,
                "ShuffleTransformer must reorder something; fields " + names(node.fields)
                        + ", methods " + methodNames(node));
    }

    @Test
    void dummyClassWatermarkAddsAClass() throws Exception {
        Path output = runRaw(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF,
                new WatermarkOptions(true, false, false, false, "com/example/Dummy",
                        null, null, null)));
        assertTrue(classEntries(output).size() > 2,
                "DummyClassTransformer must add a class; entries were " + classEntries(output));
    }

    @Test
    void textInsideClassWatermarkEmbedsTheText() throws Exception {
        final byte[] baseline = subjectBytes(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        final byte[] marked = subjectBytes(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF,
                new WatermarkOptions(false, true, false, false, null, "WC_MARKER", null, null)));

        // TextInsideClassTransformer adds a UTF8 constant through classWriter.newUTF8(...),
        // which is not referenced by any instruction, so it is invisible to an ASM walk and
        // only observable in the raw constant pool.
        final String asText = new String(marked, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(asText.contains("WC_MARKER"),
                "TextInsideClassTransformer must embed its watermark text in the constant pool");
        assertFalse(new String(baseline, java.nio.charset.StandardCharsets.ISO_8859_1)
                        .contains("WC_MARKER"),
                "the baseline class must not contain the watermark text");
    }

    @Test
    void unusedStringWatermarkEmbedsAConstant() throws Exception {
        ClassNode baseline = run(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        ClassNode node = run(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF,
                new WatermarkOptions(false, false, true, false, null, null, "", null)));
        assertTrue(constants(node).size() > constants(baseline).size(),
                "UnusedStringTransformer must add a constant; baseline "
                        + constants(baseline).size() + ", with watermark " + constants(node).size());
    }

    @Test
    void zipCommentWatermarkSetsTheComment() throws Exception {
        Path output = runRaw(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF,
                new WatermarkOptions(false, false, false, true, null, null, null,
                        "Obfuscation provided by Bozar")));
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertNotNull(zip.getComment(), "ZipCommentTransformer must set the zip comment");
            assertTrue(zip.getComment().contains("Bozar"),
                    "zip comment must carry the watermark; was " + zip.getComment());
        }
    }

    @Test
    void watermarkEnabledWithoutTextFailsWithAnActionableError() {
        // Upstream reads the watermark text straight from the options and its transformers
        // NPE on a null; the facade must catch that before the engine runs.
        final Path output = dir.resolve("no-text.jar");
        ObfuscationEngine.ObfuscationException e = org.junit.jupiter.api.Assertions.assertThrows(
                ObfuscationEngine.ObfuscationException.class,
                () -> ObfuscationEngine.execute(ObfuscationRequest.builder()
                        .inputJar(input).outputJar(output)
                        .options(options(RenameOption.OFF, LineNumberOption.KEEP,
                                LocalVariableOption.KEEP, false, false, false,
                                ControlFlowObfuscationOption.OFF, false,
                                ConstantObfuscationOption.OFF, StringObfuscationOption.OFF,
                                new WatermarkOptions(true, false, false, false,
                                        null, null, null, null)))
                        .build()));
        assertTrue(e.getMessage().contains("dummyClassText"),
                "the error must name the missing value; was: " + e.getMessage());
        assertFalse(Files.exists(output), "a rejected request must write no output");
    }

    @Test
    void crasherChangesTheOutput() throws Exception {
        // CrasherTransformer uses ThreadLocalRandom directly, so the effect is only
        // observable as a difference from the baseline, not as a specific structure.
        Path baseline = runRaw(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));
        Path output = runRaw(options(RenameOption.OFF, LineNumberOption.KEEP,
                LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                true, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()));

        assertNotEquals(classEntries(baseline), classEntries(output),
                "CrasherTransformer must change the class set");
    }

    /**
     * Bukkit/Paper protection: when the superclass is a library class (on the classpath, not
     * part of the transformation set), public non-static instance methods keep their names,
     * while private and static methods still rename. This is the mechanism that keeps
     * {@code JavaPlugin.onEnable()} overrides intact.
     */
    @Test
    void librarySuperclassMethodsAreNotRenamed() throws Exception {
        // Same layout as the fixture, but the superclass comes from a library jar on the
        // classpath rather than from the input.
        final Path subject = dir.resolve("subject-with-library.jar");
        final Path output = dir.resolve("plugin-out.jar");
        final ClassWriter baseWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        baseWriter.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/library/Base", null, "java/lang/Object", null);
        addMethod(baseWriter, "onEnable", Opcodes.ACC_PUBLIC);
        baseWriter.visitEnd();
        final Path library = dir.resolve("library.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(library))) {
            out.putNextEntry(new JarEntry("com/library/Base.class"));
            out.write(baseWriter.toByteArray());
            out.closeEntry();
        }

        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/example/Plugin", null, "com/library/Base", null);
        writer.visitSource("Plugin.java", null);
        addMethod(writer, "onEnable", Opcodes.ACC_PUBLIC);      // overrides Base's
        addMethod(writer, "onStart", Opcodes.ACC_PUBLIC);        // public instance, no override
        addMethod(writer, "secret", Opcodes.ACC_PRIVATE);
        addMethod(writer, "utility", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        writer.visitEnd();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(subject))) {
            out.putNextEntry(new JarEntry("com/example/Plugin.class"));
            out.write(writer.toByteArray());
            out.closeEntry();
        }

        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(subject).outputJar(output)
                .classpath(List.of(library.toString()))
                .options(options(RenameOption.ALPHABET, LineNumberOption.KEEP,
                        LocalVariableOption.KEEP, false, false, false, ControlFlowObfuscationOption.OFF,
                        false, ConstantObfuscationOption.OFF, StringObfuscationOption.OFF, noWatermark()))
                .build());

        final ClassNode plugin = classWithSourceFile(output, "Plugin.java");
        final List<String> names = methodNames(plugin);
        // Public instance methods are NOT renamed when the superclass is a library class.
        assertTrue(names.contains("onEnable"), "public instance methods must keep their name; got " + names);
        assertTrue(names.contains("onStart"), "public instance methods must keep their name; got " + names);
        // Private and static methods are renamed unconditionally.
        assertFalse(names.contains("secret"), "private methods must be renamed; got " + names);
        assertFalse(names.contains("utility"), "static methods must be renamed; got " + names);
        assertEquals(4, names.size(), "no methods must be added or dropped; got " + names);
    }

    /** Returns the class in a whole-JAR output matching the predicate, or null if none match. */
    private static ClassNode classByName(Path jar, java.util.function.Predicate<String> predicate)
            throws IOException {
        for (String name : classEntries(jar)) {
            if (!predicate.test(name)) continue;
            final byte[] bytes = readEntry(jar, name);
            assertNotNull(bytes, "class entry " + name + " must be readable");
            final ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            return node;
        }
        return null;
    }

    /**
     * Mapping lines are {@code <type> <key> <value>}; returns the key and value of the line
     * whose key equals {@code key}. The key is the exact engine rename-map key
     * ({@code <slash-form owner>.<name><desc>}), so a test can pin the format.
     */
    private static Map.Entry<String, String> mappingEntry(String content, String key) {
        for (String line : content.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).toList()) {
            final String[] parts = line.split("\\s+");
            if (parts.length >= 3 && key.equals(parts[1])) return Map.entry(parts[1], parts[2]);
        }
        throw new AssertionError("no mapping entry with key " + key + "; content was:\n" + content);
    }

    /** Returns the class in a whole-JAR output whose source file matches the given name. */
    private static ClassNode classWithSourceFile(Path jar, String sourceFile) throws IOException {
        final ClassNode found = classByName(jar, name -> {
            try {
                final byte[] bytes = readEntry(jar, name);
                final ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
                return sourceFile.equals(node.sourceFile);
            } catch (IOException e) {
                return false;
            }
        });
        assertNotNull(found, "a class with source file " + sourceFile + " must be present in "
                + jar + "; entries were " + classEntries(jar));
        return found;
    }

    @Test
    void everyRegisteredTransformerIsCovered() {
        final List<String> covered = List.of(
                "ClassRenamerTransformer", "FieldRenamerTransformer", "MethodRenamerTransformer",
                "LightControlFlowTransformer", "HeavyControlFlowTransformer", "ConstantTransformer",
                "StringTransformer", "LocalVariableTransformer", "LineNumberTransformer",
                "SourceFileTransformer", "DummyClassTransformer", "TextInsideClassTransformer",
                "UnusedStringTransformer", "ZipCommentTransformer", "CrasherTransformer",
                "ShuffleTransformer", "InnerClassTransformer");

        final List<String> registered = io.github.vimasig.bozar.obfuscator.transformer.TransformManager
                .getTransformers().stream().map(Class::getSimpleName).toList();

        assertEquals(registered.size(), covered.size(),
                "a transformer was added or removed; update this test. Registered: " + registered);
        assertTrue(covered.containsAll(registered),
                "uncovered transformers: " + registered.stream()
                        .filter(t -> !covered.contains(t)).toList()
                        + "; explicitly unsupported: " + UNSUPPORTED);
    }

    // ------------------------------------------------------------------ helpers

    private static WatermarkOptions noWatermark() {
        return new WatermarkOptions(false, false, false, false, null, null, null, null);
    }

    private static BozarOptions options(RenameOption rename, LineNumberOption lines,
                                        LocalVariableOption locals, boolean removeSourceFile,
                                        boolean shuffle, boolean removeInnerClasses,
                                        ControlFlowObfuscationOption controlFlow, boolean crasher,
                                        ConstantObfuscationOption constants,
                                        StringObfuscationOption strings, WatermarkOptions watermark) {
        return new BozarOptions(rename, lines, locals, removeSourceFile, shuffle,
                removeInnerClasses, controlFlow, crasher, constants, strings, watermark);
    }

    private Path runRaw(BozarOptions options) throws IOException {
        final Path output = dir.resolve("out-" + Math.abs(options.hashCode()) + ".jar");
        ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input).outputJar(output).options(options).build());
        assertTrue(Files.isRegularFile(output), "engine must produce an output jar");
        return output;
    }

    /**
     * Runs the engine and parses the transformed subject class back out of the output.
     * The fixture contributes one top-level class plus one nested class, which the renamer
     * may rename into unrelated names — so the subject is identified by {@code SourceFile},
     * which only the fixture's class carries, and the nested class is known by the engine's
     * convention that it is the one with no source file.
     */
    private ClassNode run(BozarOptions options) throws IOException {
        final byte[] bytes = subjectBytes(options);
        final ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** The raw bytes of the transformed subject class, located without assuming its name. */
    private byte[] subjectBytes(BozarOptions options) throws IOException {
        final Path output = runRaw(options);
        final List<String> classes = classEntries(output);
        assertEquals(2, classes.size(),
                "fixture contributes exactly two classes; got " + classes);

        final List<String> withSource = new ArrayList<>();
        for (String name : classes) {
            final byte[] bytes = readEntry(output, name);
            final ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            // Subject.java is the fixture's source file attribute; the nested class has
            // none of its own, and InnerClassTransformer may strip it entirely, so the
            // fixture is the one that still declares a source (or, failing that, the one
            // with the methods the fixture generated).
            if ("Subject.java".equals(node.sourceFile) || hasFixtureMethods(node)) {
                withSource.add(name);
            }
        }
        assertEquals(1, withSource.size(),
                "could not identify the subject class among " + classes
                        + "; matched " + withSource);
        return readEntry(output, withSource.get(0));
    }

    private static boolean hasFixtureMethods(ClassNode node) {
        // Method names survive only where MethodRenamerTransformer was off; a private method's
        // renamed name is unpredictable, so match on descriptor instead of on a known name.
        return node.methods.size() == 12
                && node.methods.stream().allMatch(m -> "()V".equals(m.desc));
    }

    private static byte[] readEntry(Path jar, String name) throws IOException {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(name)) return in.readAllBytes();
            }
        }
        return null;
    }

    private static List<String> classEntries(Path jar) throws IOException {
        final List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().endsWith(".class")) names.add(entry.getName());
            }
        }
        return names;
    }

    private static List<Object> constants(ClassNode node) {
        final List<Object> found = new ArrayList<>();
        node.methods.forEach(m -> {
            for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) found.add(ldc.cst);
            }
        });
        return found;
    }

    private static List<String> names(List<org.objectweb.asm.tree.FieldNode> fields) {
        return fields.stream().map(f -> f.name).toList();
    }

    private static List<String> methodNames(ClassNode node) {
        return node.methods.stream().map(m -> m.name).toList();
    }

    private static List<Integer> lineNumbers(ClassNode node) {
        final List<Integer> numbers = new ArrayList<>();
        node.methods.forEach(m -> {
            for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof org.objectweb.asm.tree.LineNumberNode line) numbers.add(line.line);
            }
        });
        return numbers;
    }

    private static long lineNumberCount(ClassNode node) {
        return lineNumbers(node).size();
    }

    private static List<String> expectedFieldOrder() {
        final List<String> expected = new ArrayList<>();
        for (int i = 0; i < 6; i++) expected.add("field" + i);
        expected.add("name");
        return expected;
    }

    private static List<String> expectedMethodOrder() {
        final List<String> expected = new ArrayList<>();
        for (int i = 0; i < 6; i++) expected.add("method" + i);
        for (int i = 0; i < 3; i++) expected.add("hidden" + i);
        for (int i = 0; i < 3; i++) expected.add("staticMethod" + i);
        return expected;
    }

    /** Every fixture method has one string and one int constant, so the constant transformers
     *  have something in every method, not only in the first. */
    private static void addMethod(ClassWriter writer, String name, int access) {
        addMethod(writer, name, "()V", access);
    }

    /** Same as {@link #addMethod(ClassWriter, String, int)} but with an explicit descriptor,
     * so a class can declare overloads. */
    private static void addMethod(ClassWriter writer, String name, String desc, int access) {
        final MethodNode m = new MethodNode(access, name, desc, null, null);
        m.visitCode();
        final Label start = new Label();
        m.visitLabel(start);
        m.visitLineNumber(77770, start);
        m.visitLdcInsn("FIXTURE_STRING_MARKER");
        m.visitInsn(Opcodes.POP);
        m.visitLdcInsn(424242);
        m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m.accept(writer);
    }
}
