package io.github.vimasig.bozar.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

/** Debug-symbol removal. Debug info is the most fragile part of obfuscation output, so
 * each item is individually togglable and all are off unless a profile enables them. */
public class BozarDebugExtension {

    private final Property<Boolean> removeSourceFile;
    private final Property<Boolean> removeLineNumbers;
    private final Property<Boolean> removeLocalVariables;

    public BozarDebugExtension(ObjectFactory objects) {
        this.removeSourceFile = objects.property(Boolean.class);
        this.removeLineNumbers = objects.property(Boolean.class);
        this.removeLocalVariables = objects.property(Boolean.class);
    }

    public Property<Boolean> getRemoveSourceFile() { return removeSourceFile; }
    public Property<Boolean> getRemoveLineNumbers() { return removeLineNumbers; }
    public Property<Boolean> getRemoveLocalVariables() { return removeLocalVariables; }

    public void removeSourceFile(boolean v) { removeSourceFile.set(v); }
    public void setRemoveSourceFile(boolean v) { removeSourceFile.set(v); }

    public void removeLineNumbers(boolean v) { removeLineNumbers.set(v); }
    public void setRemoveLineNumbers(boolean v) { removeLineNumbers.set(v); }

    public void removeLocalVariables(boolean v) { removeLocalVariables.set(v); }
    public void setRemoveLocalVariables(boolean v) { removeLocalVariables.set(v); }
}
