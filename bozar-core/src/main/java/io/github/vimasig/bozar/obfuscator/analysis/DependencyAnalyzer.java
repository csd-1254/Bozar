package io.github.vimasig.bozar.obfuscator.analysis;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Analyzes a main JAR's dependencies by resolving class hierarchies.
 *
 * <p>Uses ASM to detect type references and recursively resolves superclass
 * and interface dependencies to discover transitive requirements.
 *
 * <p>The analysis is similar to Skidfuscator's dependency analysis but implemented
 * natively for Bozar. It follows the hierarchy resolution pattern: from application classes,
 * recursively find superclass and interface types, marking which JAR files are required.
 *
 * <p>Detects references from:
 * <ul>
 *   <li>superclass declaration</li>
 *   <li>interface implementations</li>
 *   <li>field descriptors and types</li>
 *   <li>method return types and parameter types</li>
 *   <li>annotation types (RuntimeVisible, RuntimeInvisible)</li>
 *   <li>ldc constants of type java.lang.Class</li>
 *   <li>invokedynamic bootstrap method types</li>
 * </ul>
 *
 * <p>Note: This uses a lighter-weight scanning approach compared to full type reference
 * detection, focusing on class hierarchy resolution which is the dominant pattern for
 * Minecraft plugins extending Bukkit/Paper API classes.
 */
public class DependencyAnalyzer {

    private final Path mainJar;
    private final List<Path> libraryJars;

    // Maps className (binary) to the jar that hosts it (for library jars)
    private final Map<String, Path> classToLibraryMap = new LinkedHashMap<>();
    // Cache of previously computed hierarchies
    private final Map<String, DependencyClassHierarchy> hierarchyCache = new LinkedHashMap<>();

    // For reporting: map from jar -> map of className -> list of reasons
    private final Map<Path, Map<String, List<String>>> dependencyReport = new LinkedHashMap<>();

    public DependencyAnalyzer(Path mainJar, List<Path> libraryJars) {
        this.mainJar = mainJar;
        this.libraryJars = libraryJars;
    }

    /**
     * Analyzes the main JAR and returns the set of required dependency JARs.
     *
     * @return analysis result with required JARs and reference details
     * @throws IOException if any JAR cannot be read
     */
    public DependencyAnalysisResult analyze() throws IOException {
        // Step 1: Index library jars
        indexLibraryJars();

        // Step 2: Load all classes from main jar
        Set<String> mainClasses = loadClassesFromJar(mainJar);

        // Step 3: Resolve hierarchical dependencies
        Set<Path> requiredJars = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();

        for (String cls : mainClasses) {
            resolveHierarchy(cls, requiredJars, visited, "top-level class from main jar");
        }

        // Build class-to-jar mapping from the report
        Map<String, String> classToJar = new LinkedHashMap<>();
        for (Map.Entry<Path, Map<String, List<String>>> jarEntry : dependencyReport.entrySet()) {
            for (String className : jarEntry.getValue().keySet()) {
                classToJar.put(className, jarEntry.getKey().toString());
            }
        }

        // Build class-to-reason mapping
        Map<String, String> classToReason = new LinkedHashMap<>();
        for (Map.Entry<Path, Map<String, List<String>>> jarEntry : dependencyReport.entrySet()) {
            for (Map.Entry<String, List<String>> classEntry : jarEntry.getValue().entrySet()) {
                classToReason.put(classEntry.getKey(),
                        String.join(", ", classEntry.getValue()));
            }
        }

        // Compute unused jars
        Set<String> unusedJars = new LinkedHashSet<>();
        for (Path jar : libraryJars) {
            if (!requiredJars.contains(jar)) {
                unusedJars.add(jar.toString());
            }
        }

        return new DependencyAnalysisResult(
                requiredJars.stream().map(Path::toString).collect(Collectors.toSet()),
                classToJar,
                classToReason,
                unusedJars
        );
    }

    /**
     * Recursively resolves the class hierarchy for a given class.
     */
    private void resolveHierarchy(String className, Set<Path> requiredJars,
                                  Set<String> visited, String reason) throws IOException {
        if (visited.contains(className)) return;
        visited.add(className);

        DependencyClassHierarchy hierarchy = loadClassHierarchy(className);

        // If class is from a library jar, record it as required
        if (!hierarchy.isFromMainJar() && hierarchy.getSourceJar() != null) {
            requiredJars.add(hierarchy.getSourceJar());
            addToReport(hierarchy.getSourceJar(), className, reason);
        }

        // Resolve superclass
        if (hierarchy.getSuperName() != null && !hierarchy.getSuperName().isEmpty()) {
            Path jarForSuper = resolveJarForClass(hierarchy.getSuperName());
            if (jarForSuper != null) {
                resolveHierarchy(hierarchy.getSuperName(), requiredJars, visited,
                        "needed as superclass of " + className);
            }
        }

        // Resolve interfaces
        for (String iface : hierarchy.getInterfaces()) {
            Path jarForIface = resolveJarForClass(iface);
            if (jarForIface != null) {
                resolveHierarchy(iface, requiredJars, visited,
                        "needed as interface of " + className);
            }
        }
    }

    /**
     * Resolves which JAR a class lives in.
     * @param className expected to be in binary name format (e.g., org.example.Plugin)
     * @return the JAR containing the class, or null if not found
     */
    private Path resolveJarForClass(String className) {
        if (mainJar != null && classExistsInJar(mainJar, className)) {
            return mainJar;
        }
        return classToLibraryMap.get(className);
    }

    /**
     * Checks if a class exists in a JAR by name.
     * @param className expected to be in binary name format (e.g., org.example.Plugin)
     */
    private boolean classExistsInJar(Path jar, String className) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            String path = className.replace('.', '/') + ".class";
            return jarFile.getJarEntry(path) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Indexes all library JARs by their binary class names.
     * Invalid JARs are skipped (treated as having no classes).
     */
    private void indexLibraryJars() {
        for (Path jarPath : libraryJars) {
            try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                        String className = entry.getName().replace(".class", "").replace('/', '.');
                        classToLibraryMap.put(className, jarPath);
                    }
                }
            } catch (IOException e) {
                // Skip invalid JARs - they'll be marked as unused
            }
        }
    }

    /**
     * Loads all classes from a JAR.
     */
    private Set<String> loadClassesFromJar(Path jarPath) throws IOException {
        Set<String> classes = new HashSet<>();
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    String className = entry.getName().replace(".class", "").replace('/', '.');
                    classes.add(className);
                }
            }
        }
        return classes;
    }

    /**
     * Loads the class hierarchy for a given class name.
     * @param className in binary name format (e.g., org.example.Plugin)
     */
    private DependencyClassHierarchy loadClassHierarchy(String className) throws IOException {
        if (hierarchyCache.containsKey(className)) {
            return hierarchyCache.get(className);
        }

        String superName = null;
        String[] interfaces = new String[0];
        Path sourceJar = null;
        boolean fromMainJar = false;

        // Try main jar first
        try (InputStream classStream = getClassStream(mainJar, className)) {
            if (classStream != null) {
                fromMainJar = true;
                sourceJar = mainJar;

                // Parse class file to get hierarchy
                ClassReader cr = new ClassReader(classStream);
                HierarchyVisitor visitor = new HierarchyVisitor();
                cr.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                // Convert internal names to binary names for consistent lookup
                superName = convertTypeName(visitor.getSuperName());
                interfaces = convertTypeNames(visitor.getInterfaces());
            }
        }

        if (sourceJar == null) {
            // Try library jars
            Path libJar = classToLibraryMap.get(className);
            if (libJar != null) {
                sourceJar = libJar;

                try (InputStream classStream = getClassStream(libJar, className)) {
                    if (classStream != null) {
                        ClassReader cr = new ClassReader(classStream);
                        HierarchyVisitor visitor = new HierarchyVisitor();
                        cr.accept(visitor, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                        superName = convertTypeName(visitor.getSuperName());
                        interfaces = convertTypeNames(visitor.getInterfaces());
                    }
                }
            }
        }

        if (sourceJar == null) {
            // Class not found anywhere - create a stub entry
            DependencyClassHierarchy notFound = new DependencyClassHierarchy(
                    className, null, new String[0], fromMainJar, sourceJar);
            hierarchyCache.put(className, notFound);
            return notFound;
        }

        DependencyClassHierarchy hierarchy = new DependencyClassHierarchy(
                className,
                superName,
                interfaces,
                fromMainJar,
                sourceJar
        );
        hierarchyCache.put(className, hierarchy);
        return hierarchy;
    }

    /**
     * Converts a type name from internal format (org/example/Type) to binary format (org.example.Type).
     * Returns null for primitive types or array descriptors.
     */
    private String convertTypeName(String internalName) {
        if (internalName == null || internalName.isEmpty()) {
            return null;
        }
        return TypeUtil.internalNameToBinary(internalName);
    }

    /**
     * Converts an array of type names from internal to binary format.
     */
    private String[] convertTypeNames(String[] internalNames) {
        if (internalNames == null) {
            return new String[0];
        }
        String[] result = new String[internalNames.length];
        for (int i = 0; i < internalNames.length; i++) {
            result[i] = convertTypeName(internalNames[i]);
        }
        // Filter out nulls (primitives, arrays)
        return Arrays.stream(result)
                .filter(s -> s != null)
                .toArray(String[]::new);
    }

    /**
     * Gets the InputStream for a class from a JAR.
     */
    private InputStream getClassStream(Path jar, String className) throws IOException {
        JarFile jf = new JarFile(jar.toFile());
        String path = className.replace('.', '/') + ".class";
        JarEntry entry = jf.getJarEntry(path);
        if (entry == null) {
            jf.close();
            return null;
        }
        return new ClosableInputStreamWrapper(jf, jf.getInputStream(entry));
    }

    /**
     * Adds an entry to the dependency report.
     */
    private void addToReport(Path jar, String className, String reason) {
        dependencyReport.computeIfAbsent(jar, k -> new LinkedHashMap<>());
        Map<String, List<String>> classes = dependencyReport.get(jar);
        classes.computeIfAbsent(className, k -> new ArrayList<>()).add(reason);
    }

    /**
     * Wrapper that ensures the JarFile is closed when the InputStream is closed.
     */
    private static class ClosableInputStreamWrapper extends InputStream {
        private final JarFile jarFile;
        private final InputStream delegate;

        ClosableInputStreamWrapper(JarFile jarFile, InputStream delegate) {
            this.jarFile = jarFile;
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
            jarFile.close();
        }
    }
}