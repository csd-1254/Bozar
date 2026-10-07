package io.github.vimasig.bozar.obfuscator.analysis;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of analyzing a plugin's dependency requirements.
 *
 * <p>Tracks which dependency JARs are required for the application to resolve,
 * and why each class from those JARs is referenced.
 */
public final class DependencyAnalysisResult {

    private final Set<String> requiredJars;
    private final Map<String, String> classToJar;
    private final Map<String, String> classToReason;
    private final Set<String> unusedJars;

    public DependencyAnalysisResult(Set<String> requiredJars,
                                    Map<String, String> classToJar,
                                    Map<String, String> classToReason,
                                    Set<String> unusedJars) {
        this.requiredJars = Set.copyOf(requiredJars);
        this.classToJar = Map.copyOf(classToJar);
        this.classToReason = Map.copyOf(classToReason);
        this.unusedJars = Set.copyOf(unusedJars);
    }

    /** Returns the set of dependency JAR paths that are required. */
    public Set<String> getRequiredJars() {
        return requiredJars;
    }

    /** Maps binary class names to the JAR that contains them. */
    public Map<String, String> getClassToJar() {
        return classToJar;
    }

    /** Maps binary class names to a human-readable reason for the reference. */
    public Map<String, String> getClassToReason() {
        return classToReason;
    }

    /** Returns unknown JARs that were candidates but not required. Useful for diagnostics. */
    public Set<String> getUnusedJars() {
        return unusedJars;
    }

    /** Creates an empty result (no dependencies required). */
    public static DependencyAnalysisResult empty() {
        return new DependencyAnalysisResult(Collections.emptySet(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptySet());
    }

    /** Merges multiple analysis results (union of required jars, merged class maps). */
    public DependencyAnalysisResult mergeWith(DependencyAnalysisResult other) {
        if (other == this) return this;
        final Set<String> mergedJars = new LinkedHashSet<>(this.requiredJars);
        mergedJars.addAll(other.requiredJars);
        final Map<String, String> mergedClassToJar = new LinkedHashMap<>(this.classToJar);
        mergedClassToJar.putAll(other.classToJar);
        final Map<String, String> mergedClassToReason = new LinkedHashMap<>(this.classToReason);
        mergedClassToReason.putAll(other.classToReason);
        final Set<String> mergedUnused = new LinkedHashSet<>(this.unusedJars);
        mergedUnused.addAll(other.unusedJars);
        return new DependencyAnalysisResult(mergedJars, mergedClassToJar, mergedClassToReason, mergedUnused);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("DependencyAnalysisResult{requiredJars=").append(requiredJars.size())
                .append(", referencedClasses=").append(classToJar.size())
                .append(", unusedJars=").append(unusedJars.size()).append("}\n");

        for (String jar : requiredJars) {
            sb.append("  ").append(jar).append(":\n");
            final var classes = classToJar.entrySet().stream()
                    .filter(e -> jar.equals(e.getValue()))
                    .toList();
            for (var entry : classes) {
                final String reason = classToReason.get(entry.getKey());
                sb.append("    ").append(entry.getKey()).append(" (").append(reason).append(")\n");
            }
        }

        if (!unusedJars.isEmpty()) {
            sb.append("  Unused candidate JARs:\n");
            for (String jar : unusedJars) {
                sb.append("    ").append(jar).append("\n");
            }
        }
        return sb.toString();
    }
}