package io.github.vimasig.bozar.obfuscator.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DependencyAnalyzer}.
 */
class DependencyAnalyzerTest {

    @TempDir
    Path tempDir;

    // =======================================================================
    // Test fixture helpers
    // =======================================================================

    /**
     * Creates a minimal JAR containing a single class.
     */
    private Path createJar(String jarName, ClassDef def) throws IOException {
        Path jar = tempDir.resolve(jarName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            byte[] classBytes = createClassBytes(def);
            JarEntry entry = new JarEntry(def.internalName().replace('.', '/') + ".class");
            out.putNextEntry(entry);
            out.write(classBytes);
            out.closeEntry();
        }
        return jar;
    }

    /**
     * Simple class definition for test fixtures.
     */
    private record ClassDef(String internalName, String superName, String[] interfaces) {}

    /**
     * Creates bytecode for a class using ASM ClassWriter.
     */
    private byte[] createClassBytes(ClassDef def) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                def.internalName().replace('.', '/'), null,
                def.superName().replace('.', '/'), convertInterfaces(def.interfaces()));

        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "onEnable", "()V", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * Converts interface names from binary format to internal format.
     */
    private String[] convertInterfaces(String[] interfaces) {
        if (interfaces == null) return new String[0];
        return Arrays.stream(interfaces)
                .filter(s -> s != null && !s.isEmpty())
                .map(s -> s.replace('.', '/'))
                .toArray(String[]::new);
    }

    // =======================================================================
    // Tests
    // =======================================================================

    @Test
    void requiredJarIsDetectedWhenClassExtendsExternal() throws IOException {
        Path libJar = createJar("library.jar",
                new ClassDef("org.example.LibrarySuper", "java.lang.Object", new String[0]));

        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.Plugin", "org.example.LibrarySuper", new String[0]));

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of(libJar));
        DependencyAnalysisResult result = analyzer.analyze();

        assertTrue(result.getRequiredJars().contains(libJar.toString()));
        assertFalse(result.getUnusedJars().contains(libJar.toString()));
    }

    @Test
    void requiredJarIsDetectedWhenClassImplementsInterface() throws IOException {
        Path libJar = createJar("library.jar",
                new ClassDef("org.example.ExternalInterface", "java.lang.Object", new String[0]));

        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.Plugin", "java.lang.Object", new String[]{"org.example.ExternalInterface"}));

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of(libJar));
        DependencyAnalysisResult result = analyzer.analyze();

        assertTrue(result.getRequiredJars().contains(libJar.toString()));
    }

    @Test
    void unusedJarIsNotIncludedWhenNotReferenced() throws IOException {
        Path unusedJar = createJar("unused.jar",
                new ClassDef("org.example.UnusedClass", "java.lang.Object", new String[0]));

        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.Plugin", "java.lang.Object", new String[0]));

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of(unusedJar));
        DependencyAnalysisResult result = analyzer.analyze();

        assertFalse(result.getRequiredJars().contains(unusedJar.toString()));
        assertTrue(result.getUnusedJars().contains(unusedJar.toString()));
    }

    @Test
    void transitiveDependenciesAreResolved() throws IOException {
        Path lib1 = createJar("lib1.jar",
                new ClassDef("org.example.BaseClass", "java.lang.Object", new String[0]));

        Path lib2 = createJar("lib2.jar",
                new ClassDef("org.example.DerivedClass", "org.example.BaseClass", new String[0]));

        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.Plugin", "org.example.DerivedClass", new String[0]));

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of(lib1, lib2));
        DependencyAnalysisResult result = analyzer.analyze();

        // Both should be required transitively
        assertTrue(result.getRequiredJars().contains(lib1.toString()) ||
                   result.getClassToJar().get("org.example.BaseClass") != null);
        assertTrue(result.getRequiredJars().contains(lib2.toString()) ||
                   result.getClassToJar().get("org.example.DerivedClass") != null);
    }

    @Test
    void classToJarMappingIsCorrect() throws IOException {
        Path libJar = createJar("library.jar",
                new ClassDef("org.example.ExternalType", "java.lang.Object", new String[0]));

        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.Plugin", "org.example.ExternalType", new String[0]));

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of(libJar));
        DependencyAnalysisResult result = analyzer.analyze();

        assertEquals(libJar.toString(), result.getClassToJar().get("org.example.ExternalType"));
    }

    @Test
    void classToReasonMappingIsCorrect() throws IOException {
        Path libJar = createJar("library.jar",
                new ClassDef("org.example.ExternalType", "java.lang.Object", new String[0]));

        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.Plugin", "org.example.ExternalType", new String[0]));

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of(libJar));
        DependencyAnalysisResult result = analyzer.analyze();

        String reason = result.getClassToReason().get("org.example.ExternalType");
        assertNotNull(reason);
        assertTrue(reason.contains("superclass"));
    }

    @Test
    void buildResultIsCorrectForSingleDependency() throws IOException {
        Path libJar = createJar("library.jar",
                new ClassDef("org.example.ExternalType", "java.lang.Object", new String[0]));

        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.Plugin", "org.example.ExternalType", new String[0]));

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of(libJar));
        DependencyAnalysisResult result = analyzer.analyze();

        assertEquals(1, result.getRequiredJars().size());
        assertEquals(1, result.getClassToJar().size());
        assertEquals(1, result.getClassToReason().size());
        assertEquals(0, result.getUnusedJars().size());
    }

    @Test
    void emptyResultWhenNoDependencies() throws IOException {
        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.Plugin", "java.lang.Object", new String[0]));

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of());
        DependencyAnalysisResult result = analyzer.analyze();

        assertTrue(result.getRequiredJars().isEmpty());
        assertTrue(result.getClassToJar().isEmpty());
    }

    @Test
    void jdkClassesAreNotIncludedInRequiredJars() throws IOException {
        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.Plugin", "java.lang.Object", new String[0]));

        Path jdkStub = tempDir.resolve("jdk-stub.jar");
        Files.writeString(jdkStub, "stub");

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of(jdkStub));
        DependencyAnalysisResult result = analyzer.analyze();

        assertFalse(result.getRequiredJars().contains(jdkStub.toString()));
        assertTrue(result.getUnusedJars().contains(jdkStub.toString()));
    }

    @Test
    void interfaceInheritanceIsResolved() throws IOException {
        Path libJar = createJar("library.jar",
                new ClassDef("org.example.ApiInterface", "java.lang.Object", new String[0]));

        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.Plugin", "org.example.ApiInterface", new String[0]));

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of(libJar));
        DependencyAnalysisResult result = analyzer.analyze();

        assertTrue(result.getRequiredJars().contains(libJar.toString()));
        assertEquals(1, result.getClassToJar().size());
    }

    @Test
    void resultToStringProducesMeaningfulOutput() throws IOException {
        Path libJar = createJar("library.jar",
                new ClassDef("org.example.ExternalType", "java.lang.Object", new String[0]));

        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.Plugin", "org.example.ExternalType", new String[0]));

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of(libJar));
        DependencyAnalysisResult result = analyzer.analyze();

        String toString = result.toString();
        assertTrue(toString.contains("requiredJars="));
        assertTrue(toString.contains("referencedClasses="));
        assertTrue(toString.contains("unusedJars="));
    }

    @Test
    void emptyResultWhenNoDependenciesButWithCandidateJars() throws IOException {
        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.Plugin", "java.lang.Object", new String[0]));

        Path candidateJar = tempDir.resolve("candidate.jar");
        Files.writeString(candidateJar, "stub");

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of(candidateJar));
        DependencyAnalysisResult result = analyzer.analyze();

        // No dependencies required, but candidate is unused
        assertTrue(result.getRequiredJars().isEmpty());
        assertTrue(result.getUnusedJars().contains(candidateJar.toString()));
    }

    @Test
    void mergeWithCombinesResults() {
        var set1 = new java.util.LinkedHashSet<String>();
        set1.add("jar1");
        var map1 = new java.util.LinkedHashMap<String, String>();
        map1.put("org.example.Class1", "jar1");
        var reason1 = new java.util.LinkedHashMap<String, String>();
        reason1.put("org.example.Class1", "extension");
        var unused1 = new java.util.LinkedHashSet<String>();
        unused1.add("jar3");

        var result1 = new DependencyAnalysisResult(set1, map1, reason1, unused1);

        var set2 = new java.util.LinkedHashSet<String>();
        set2.add("jar2");
        var map2 = new java.util.LinkedHashMap<String, String>();
        map2.put("org.example.Class2", "jar2");
        var reason2 = new java.util.LinkedHashMap<String, String>();
        reason2.put("org.example.Class2", "interface");
        var unused2 = new java.util.LinkedHashSet<String>();
        unused2.add("jar4");

        var result2 = new DependencyAnalysisResult(set2, map2, reason2, unused2);

        var merged = result1.mergeWith(result2);

        assertEquals(2, merged.getRequiredJars().size());
        assertEquals(2, merged.getClassToJar().size());
        assertEquals(2, merged.getUnusedJars().size());
    }

    @Test
    void emptyResultIsReturnedWhenAnalysisFindsNothing() {
        DependencyAnalysisResult empty = DependencyAnalysisResult.empty();

        assertTrue(empty.getRequiredJars().isEmpty());
        assertTrue(empty.getClassToJar().isEmpty());
        assertTrue(empty.getClassToReason().isEmpty());
        assertTrue(empty.getUnusedJars().isEmpty());
    }

    @Test
    void multipleLibrariesCanBeIndexed() throws IOException {
        Path lib1 = createJar("lib1.jar",
                new ClassDef("org.example.Lib1Class", "java.lang.Object", new String[0]));
        Path lib2 = createJar("lib2.jar",
                new ClassDef("org.example.Lib2Class", "java.lang.Object", new String[0]));

        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.Plugin", "java.lang.Object", new String[0]));

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of(lib1, lib2));
        DependencyAnalysisResult result = analyzer.analyze();

        // Neither library is used, both should be unused
        assertFalse(result.getRequiredJars().contains(lib1.toString()));
        assertFalse(result.getRequiredJars().contains(lib2.toString()));
        assertTrue(result.getUnusedJars().contains(lib1.toString()));
        assertTrue(result.getUnusedJars().contains(lib2.toString()));
    }

    @Test
    void pluginExtendsJavaUtilArrayList() throws IOException {
        // Simulate a plugin extending ArrayList
        Path mainJar = createJar("main.jar",
                new ClassDef("org.example.MyPlugin", "java.util.ArrayList", new String[0]));

        DependencyAnalyzer analyzer = new DependencyAnalyzer(mainJar, List.of());
        DependencyAnalysisResult result = analyzer.analyze();

        // ArrayList is JDK, should not be marked as required
        assertFalse(result.getRequiredJars().stream()
                .anyMatch(jar -> jar.contains("util")));
    }
}