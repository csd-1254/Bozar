package io.github.vimasig.bozar.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

/** One on/off transformer toggle. Absent ({@code null}) means "profile decides". */
public class BozarToggleExtension {

    private final Property<Boolean> enabled;

    public BozarToggleExtension(ObjectFactory objects) {
        this.enabled = objects.property(Boolean.class);
    }

    public Property<Boolean> getEnabled() {
        return enabled;
    }

    /** Kotlin DSL: {@code enabled.set(true)}. */
    public void enabled(boolean value) {
        this.enabled.set(value);
    }

    /** Groovy DSL: {@code enabled = true}. */
    public void setEnabled(boolean value) {
        this.enabled.set(value);
    }
}
