package io.github.vimasig.bozar.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds {@code TransformerName:pattern} exclusion lines.
 *
 * <p>Exclusion scope is the class renamer only: a class that is not renamed is not
 * touched by anything else either, because fields and methods are renamed relative to
 * their owning class by the same pass.
 */
public final class KeepRules {

    private static final String CLASS_RENAMER = "ClassRenamerTransformer";

    private final Set<String> keepClasses = new LinkedHashSet<>();
    private final Set<String> keepPackages = new LinkedHashSet<>();

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
        return lines;
    }
}
