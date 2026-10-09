package io.github.vimasig.bozar.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds {@code TransformerName:pattern} exclusion lines.
 *
 * <p>{@code className}/{@code packageName} scope is the class renamer only (they emit
 * {@code ClassRenamerTransformer:}-prefixed lines): the class keeps its name but other
 * transformers (constants, strings, debug, ...) still apply. To exclude a class from
 * <em>every</em> transformer, use {@link #excludeAll(String)}, which emits an unprefixed
 * pattern the engine copies through verbatim.
 */
public final class KeepRules {

    private static final String CLASS_RENAMER = "ClassRenamerTransformer";

    private final Set<String> keepClasses = new LinkedHashSet<>();
    private final Set<String> keepPackages = new LinkedHashSet<>();
    /** Unprefixed patterns: exclude a class/package from EVERY transformer, not just renaming. */
    private final Set<String> excludeAll = new LinkedHashSet<>();

    private KeepRules() {
    }

    public static KeepRules create() {
        return new KeepRules();
    }

    /** Keep one class by its dot-separated name, e.g. {@code com.example.MyPlugin}. */
    public KeepRules className(String className) {
        if (className != null && !className.isBlank()) this.keepClasses.add(className.trim());
        return this;
    }

    /** Keep an entire package by prefix, e.g. {@code com.example.api} becomes {@code com.example.api.**}. */
    public KeepRules packageName(String packageName) {
        if (packageName != null && !packageName.isBlank()) {
            String name = packageName.trim();
            if (!name.endsWith(".**")) name = name + ".**";
            this.keepPackages.add(name);
        }
        return this;
    }

    /** Keep the plugin entrypoints so the metadata keeps pointing at real class names. */
    public KeepRules entrypoints(List<String> entrypoints) {
        entrypoints.forEach(this::className);
        return this;
    }

    /**
     * Keep every class found in {@code META-INF/services/*} files. Service loaders resolve
     * provider names by string, so a renamed provider silently stops being discovered.
     *
     * <p>Limitation (documented): providers declared by the plugin itself are kept; providers
     * from a dependency on the classpath are outside the input JAR and need no keeping.
     */
    public KeepRules serviceProviders(java.util.Collection<String> providerClassNames) {
        providerClassNames.forEach(this::className);
        return this;
    }

    public List<String> lines() {
        final List<String> lines = new ArrayList<>();
        for (String pkg : this.keepPackages) lines.add(CLASS_RENAMER + ":" + pkg);
        for (String cls : this.keepClasses) lines.add(CLASS_RENAMER + ":" + cls);
        // Unprefixed: honored by every transformer and by the JAR load/write/verify checks,
        // so a matching class is copied through completely unchanged.
        lines.addAll(this.excludeAll);
        return lines;
    }

    /**
     * Fully exclude a class or package pattern from ALL transformers (rename, constants,
     * strings, debug, control-flow, shuffle, ...). Emitted without a transformer prefix, so
     * the engine copies the class through verbatim. Dot-separated names; {@code *}/{@code **}
     * wildcards supported (e.g. {@code com.example.api.**}).
     */
    public KeepRules excludeAll(String pattern) {
        if (pattern != null && !pattern.isBlank()) this.excludeAll.add(pattern.trim());
        return this;
    }
}
