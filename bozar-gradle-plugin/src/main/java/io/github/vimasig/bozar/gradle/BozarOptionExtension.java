package io.github.vimasig.bozar.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

/**
 * Transformer option with a selectable intensity level. The level enum mirrors the matching
 * {@code BozarOptions.XxxOption} in the core so the DSL names the real choice set.
 *
 * <p>{@code enabled} is kept as a backward-compatible alias: {@code enabled = false} is equivalent
 * to {@code level = OFF}, and {@code enabled = true} is equivalent to the lightest non-OFF level.
 * An explicitly set level always wins over the enabled flag and the profile default.
 */
public class BozarOptionExtension {

    private final Property<String> level;
    private final Property<Boolean> enabled;

    public BozarOptionExtension(ObjectFactory objects) {
        // Empty string means "unset": the profile decides. Level, when set, overrides the
        // old enabled flag and the profile, so it cannot itself be empty.
        this.level = objects.property(String.class);
        this.enabled = objects.property(Boolean.class);
    }

    /** OFF, LIGHT, HEAVY, FLOW, etc. — see {@link io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions}. */
    public Property<String> getLevel() { return level; }

    public void level(String value) { level.set(value); }
    public void setLevel(String value) { level.set(value); }

    public Property<Boolean> getEnabled() { return enabled; }
    public void enabled(boolean value) { enabled.set(value); }
    public void setEnabled(boolean value) { enabled.set(value); }
}
