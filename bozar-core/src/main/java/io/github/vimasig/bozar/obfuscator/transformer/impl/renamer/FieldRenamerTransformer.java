package io.github.vimasig.bozar.obfuscator.transformer.impl.renamer;

import io.github.vimasig.bozar.obfuscator.Bozar;
import io.github.vimasig.bozar.obfuscator.transformer.RenamerTransformer;
import io.github.vimasig.bozar.obfuscator.utils.ASMUtils;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarCategory;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

public class FieldRenamerTransformer extends RenamerTransformer {

    public FieldRenamerTransformer(Bozar bozar) {
        super(bozar, "Rename", BozarCategory.STABLE);
    }

    @Override
    public void transformClass(ClassNode classNode) {
        // Map all fields in this class node and its super classes (if not mapped)
        getSuperHierarchy(classNode)
                .forEach(cn -> cn.fields.stream()
                        .filter(fieldNode -> !isEnumSyntheticField(cn, fieldNode))
                        .filter(fieldNode -> !this.getBozar().isExcluded(this, ASMUtils.getName(cn, fieldNode)))
                        .filter(fieldNode -> !this.isMapRegistered(getFieldMapFormat(cn, fieldNode)))
                        .forEach(fieldNode -> this.registerMap(getFieldMapFormat(cn, fieldNode)))
                );

        // Apply map to upper classes if a mapping is applied to a subclass
        // So our mapper can rename references that access subfields from upper classes
        getSuperHierarchy(classNode)
                .forEach(cn -> cn.fields.stream()
                        .filter(fieldNode -> this.isMapRegistered(getFieldMapFormat(cn, fieldNode)))
                        .filter(fieldNode -> !this.isMapRegistered(getFieldMapFormat(classNode, fieldNode)))
                        .forEach(fieldNode -> this.registerMap(getFieldMapFormat(classNode, fieldNode), this.map.get(getFieldMapFormat(cn, fieldNode))))
                );
    }

    private static String getFieldMapFormat(ClassNode classNode, FieldNode fieldNode) {
        return classNode.name + "." + fieldNode.name;
    }

    /**
     * Enum constant fields and the synthetic {@code $VALUES} array back the reflective enum
     * machinery ({@code Class.getEnumConstants()}, {@code Enum.valueOf}, {@code EnumMap}).
     * Renaming them produces a JAR whose enums fail at runtime, so leave them untouched.
     */
    private static boolean isEnumSyntheticField(ClassNode classNode, FieldNode fieldNode) {
        if ((classNode.access & ACC_ENUM) == 0) return false;
        return (fieldNode.access & ACC_ENUM) != 0 || fieldNode.name.equals("$VALUES");
    }

    @Override
    public BozarConfig.EnableType getEnableType() {
        return new BozarConfig.EnableType(() -> this.getBozar().getConfig().getOptions().getRename() != this.getEnableType().type(), BozarConfig.BozarOptions.RenameOption.OFF);
    }
}
