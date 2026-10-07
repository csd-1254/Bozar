package io.github.vimasig.bozar.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads Bukkit/Paper plugin metadata out of a JAR.
 *
 * <p>This deliberately does NOT parse YAML: Bukkit's {@code plugin.yml} and Paper's
 * {@code paper-plugin.yml} expose the entrypoint as a top-level scalar, and the only
 * thing the obfuscator needs is that scalar. Reading it textually keeps comment order,
 * blank lines and unrelated fields byte-identical, which is what the resource
 * preservation guarantee requires.
 *
 * <p>Limitation (documented): only top-level {@code main: <class>} keys are found.
 * Anchors, aliases, folded scalars and multiline entrypoints are not supported.
 */
public final class MinecraftMetadata {

    public static final String PLUGIN_YML = "plugin.yml";
    public static final String PAPER_PLUGIN_YML = "paper-plugin.yml";

    private MinecraftMetadata() {
    }

    /** Names of metadata resources present in the JAR, in JAR order. */
    public static List<String> metadataNames(Path jar) throws IOException {
        final List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(PLUGIN_YML) || entry.getName().equals(PAPER_PLUGIN_YML)) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }

    public static boolean hasMetadata(Path jar) throws IOException {
        return !metadataNames(jar).isEmpty();
    }

    /**
     * Dot-separated main class names from all present metadata files, deduplicated
     * and in JAR order. Empty if no metadata file declares {@code main:}.
     */
    public static List<String> entrypoints(Path jar) throws IOException {
        final Set<String> found = new LinkedHashSet<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                final String name = entry.getName();
                if (!name.equals(PLUGIN_YML) && !name.equals(PAPER_PLUGIN_YML)) continue;
                found.add(extractMain(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        found.remove(null);
        return List.copyOf(found);
    }

    /**
     * Dot-separated provider class names declared in {@code META-INF/services/*} files of
     * this JAR only. Providers contributed by a dependency on the classpath need no keeping.
     */
    public static List<String> serviceProviders(Path jar) throws IOException {
        final Set<String> found = new LinkedHashSet<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().startsWith("META-INF/services/")) continue;
                final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                for (String line : text.split("\\r?\\n", -1)) {
                    String value = line.trim();
                    if (value.isEmpty() || value.startsWith("#")) continue;
                    found.add(value);
                }
            }
        }
        found.remove(null);
        return List.copyOf(found);
    }

    private static String extractMain(String yaml) {
        final String[] lines = yaml.split("\\r?\\n", -1);
        for (String line : lines) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("#")) continue;
            // Top level only: no leading whitespace.
            if (!line.startsWith("main:")) continue;
            String value = line.substring("main:".length()).trim();
            if (value.isEmpty()) return null;
            // Strip a trailing inline comment (only on the first whitespace boundary).
            final int space = value.indexOf(' ');
            if (space > 0) value = value.substring(0, space);
            final int comment = value.indexOf('#');
            if (comment > 0) value = value.substring(0, comment).trim();
            value = stripQuotes(value);
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            final char first = value.charAt(0);
            final char last = value.charAt(value.length() - 1);
            if ((first == '\'' || first == '"') && first == last) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
