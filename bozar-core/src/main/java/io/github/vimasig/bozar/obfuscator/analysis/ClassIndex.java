package io.github.vimasig.bozar.obfuscator.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Indexes JAR files by their binary class names for fast lookup.
 * Maps "org.bukkit.plugin.java.JavaPlugin" -> "/path/to/bukkit-api.jar".
 */
public final class ClassIndex {

    private final Map<String, String> binaryNameToJar;
    private final List<String> jarPaths;

    private ClassIndex(Map<String, String> index, List<String> jarPaths) {
        this.binaryNameToJar = Collections.unmodifiableMap(index);
        this.jarPaths = List.copyOf(jarPaths);
    }

    /**
     * Creates an index from the given JAR paths.
     *
     * @param jarPaths paths to JAR files to index
     * @return an index mapping binary class names to their containing JAR
     * @throws IOException if any JAR cannot be read
     */
    public static ClassIndex fromJars(List<String> jarPaths) throws IOException {
        final Map<String, String> index = new HashMap<>();
        for (String jarPath : jarPaths) {
            indexJarClasses(jarPath, jarPath, index);
        }
        return new ClassIndex(index, jarPaths);
    }

    /** Returns the JAR path containing the given binary class name, or null if not found. */
    public String findJarFor(String binaryClassName) {
        return binaryNameToJar.get(binaryClassName);
    }

    /** Returns all candidate JAR paths that were indexed. */
    public List<String> getAllJarPaths() {
        return jarPaths;
    }

    /** Returns true if the class is known to be in some indexed JAR. */
    public boolean containsClass(String binaryClassName) {
        return binaryNameToJar.containsKey(binaryClassName);
    }

    private static void indexJarClasses(String jarPath, String originalPath, Map<String, String> index) throws IOException {
        try (var in = new ZipInputStream(Files.newInputStream(Path.of(jarPath)))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                final String name = entry.getName();
                if (name.endsWith(".class")) {
                    final String binaryName = name.substring(0, name.length() - ".class".length())
                            .replace('/', '.');
                    index.putIfAbsent(binaryName, originalPath);
                }
            }
        }
    }
}