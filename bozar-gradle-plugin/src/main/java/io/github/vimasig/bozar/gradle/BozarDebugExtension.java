package io.github.vimasig.bozar.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

/** Debug-symbol policy. Debug info is the most fragile part of obfuscation output, so
 * each item is individually settable. The level strings mirror {@code BozarOptions}'s
 * {@code LineNumberOption} and {@code LocalVariableOption} enums: absent values inherit
 * the selected {@code Profile}'s defaults. */
public class BozarDebugExtension {

    private final Property<Boolean> removeSourceFile;
    private final Property<String> lineNumbers;
    private final Property<String> localVariables;

    public BozarDebugExtension(ObjectFactory objects) {
        this.removeSourceFile = objects.property(Boolean.class);
        this.lineNumbers = objects.property(String.class);
        this.localVariables = objects.property(String.class);
    }

    public Property<Boolean> getRemoveSourceFile() { return removeSourceFile; }
    public void removeSourceFile(boolean v) { removeSourceFile.set(v); }
    public void setRemoveSourceFile(boolean v) { removeSourceFile.set(v); }

    /** KEEP, DELETE, or RANDOMIZE. */
    public Property<String> getLineNumbers() { return lineNumbers; }
    public void lineNumbers(String v) { lineNumbers.set(v); }
    public void setLineNumbers(String v) { lineNumbers.set(v); }
    public void setRemoveLineNumbers(boolean v) { lineNumbers.set(v ? "DELETE" : "KEEP"); }

    /** KEEP, DELETE, or OBFUSCATE. */
    public Property<String> getLocalVariables() { return localVariables; }
    public void localVariables(String v) { localVariables.set(v); }
    public void setLocalVariables(String v) { localVariables.set(v); }
    public void setRemoveLocalVariables(boolean v) { localVariables.set(v ? "OBFUSCATE" : "KEEP"); }
}
