package io.github.vimasig.bozar.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

/**
 * Bukkit/Paper handling. Off by default: the plugin is a general-purpose bytecode
 * obfuscator, and forcing Minecraft keep rules on a non-plugin JAR would silently keep
 * classes the user wanted renamed.
 *
 * <p>This section only decides whether metadata is read at all. What to do with the
 * entrypoint is {@code bozar.entrypointMode}, and whether service providers are kept is
 * {@code bozar.keepServiceProviders} — deliberately not duplicated here, because a second
 * knob for the same decision is one that silently does nothing.
 */
public class BozarMinecraftExtension {

    private final Property<Boolean> enabled;

    public BozarMinecraftExtension(ObjectFactory objects) {
        this.enabled = objects.property(Boolean.class).convention(false);
    }

    public Property<Boolean> getEnabled() { return enabled; }

    public void enabled(boolean value) { this.enabled.set(value); }
    public void setEnabled(boolean value) { this.enabled.set(value); }
}
