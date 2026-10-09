package io.github.vimasig.bozar.obfuscator.utils.model;

import io.github.vimasig.bozar.obfuscator.Bozar;
import io.github.vimasig.bozar.obfuscator.transformer.impl.renamer.ClassRenamerTransformer;
import org.objectweb.asm.ClassWriter;

import java.util.Map;

public class CustomClassWriter extends ClassWriter {

    private final Bozar bozar;
    private final ClassLoader classLoader;
    public CustomClassWriter(Bozar bozar, int flags, ClassLoader classLoader) {
        super(flags);
        this.bozar = bozar;
        this.classLoader = classLoader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            return super.getCommonSuperClass(type1, type2);
        } catch (TypeNotPresentException e) {
            // The renamed types are not loadable from the classpath (only their pre-rename
            // originals are). Resolve the common super class using the original names, then map
            // the result BACK to its renamed name. Returning the original name here would bake it
            // into the recomputed StackMapTable frame, so the JVM verifier would try to load a
            // class that no longer exists under that name (NoClassDefFoundError at enable time).
            String common = super.getCommonSuperClass(this.findTypeOrDefault(type1), this.findTypeOrDefault(type2));
            return this.mapToRenamed(common);
        }
    }

    /**
     * Maps a renamed internal name back to its original (pre-rename) internal name so the type can
     * be resolved against the classpath. Returns {@code type} unchanged if it is not a renamed name.
     */
    private String findTypeOrDefault(String type) {
        var crt = this.bozar.getTransformHandler().getClassTransformer(ClassRenamerTransformer.class);
        return crt.getMap().entrySet().stream()
                .filter(entry -> entry.getValue().equals(type))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(type);
    }

    /**
     * Maps an original internal name to its renamed name. Returns {@code type} unchanged if it has
     * no rename mapping (e.g. a JDK/library class that is not obfuscated).
     */
    private String mapToRenamed(String type) {
        var crt = this.bozar.getTransformHandler().getClassTransformer(ClassRenamerTransformer.class);
        return crt.getMap().getOrDefault(type, type);
    }

    @Override
    public ClassLoader getClassLoader() {
        return this.classLoader;
    }
}
