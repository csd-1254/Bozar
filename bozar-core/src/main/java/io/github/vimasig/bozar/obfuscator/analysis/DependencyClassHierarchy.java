package io.github.vimasig.bozar.obfuscator.analysis;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable holder for class hierarchy information.
 */
public final class DependencyClassHierarchy {
    private final String className;
    private final String superName;
    private final String[] interfaces;
    private final boolean fromMainJar;
    private final Path sourceJar;

    public DependencyClassHierarchy(String className, String superName,
                                     String[] interfaces, boolean fromMainJar, Path sourceJar) {
        this.className = Objects.requireNonNull(className, "className");
        this.superName = superName;
        this.interfaces = interfaces != null ? interfaces.clone() : new String[0];
        this.fromMainJar = fromMainJar;
        this.sourceJar = sourceJar;
    }

    public String getClassName() { return className; }
    public String getSuperName() { return superName; }
    public String[] getInterfaces() { return interfaces.clone(); }
    public boolean isFromMainJar() { return fromMainJar; }
    public Path getSourceJar() { return sourceJar; }

    @Override
    public String toString() {
        return "DependencyClassHierarchy{" + className + ", super=" + superName +
                ", interfaces=" + interfaces.length + ", fromMain=" + fromMainJar + "}";
    }
}