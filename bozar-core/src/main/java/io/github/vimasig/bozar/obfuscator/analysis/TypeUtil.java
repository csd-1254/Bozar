package io.github.vimasig.bozar.obfuscator.analysis;

/**
 * Utility for converting between ASM's internal type representation and Java binary names.
 */
public final class TypeUtil {

    private TypeUtil() {}

    /**
     * Converts an internal type name (org/bukkit/JavaPlugin) to a binary name (org.bukkit.JavaPlugin).
     * Returns null for primitive types and array descriptors.
     */
    public static String internalNameToBinary(String desc) {
        if (desc == null) return null;

        // Handle various descriptor formats
        if (desc.startsWith("L") && desc.endsWith(";")) {
            // Lorg/bukkit/JavaPlugin;
            return desc.substring(1, desc.length() - 1).replace('/', '.');
        }

        // Already a binary name? Check if it contains '/' but not in array format
        if (desc.indexOf('/') > 0 && desc.indexOf('[') < 0) {
            // Might be an internal name already
            if (desc.contains(".")) {
                // Has dots, might be binary format
                return desc.replace('/', '.');
            }
            return desc.replace('/', '.');
        }

        // Handle array types (skip for now)
        if (desc.startsWith("[")) {
            // Array - could parse further but typically not needed for class resolution
            return null;
        }

        // Primitive type
        return null;
    }
}