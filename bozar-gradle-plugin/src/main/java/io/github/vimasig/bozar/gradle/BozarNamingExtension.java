package io.github.vimasig.bozar.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

/**
 * Naming policy. The seed makes a given configuration reproducible: same input, same seed,
 * same output content. Not the same bytes — the engine stamps ZIP entries with the wall
 * clock (docs/12 section 2.6). Unseeded runs keep the upstream behavior (a fresh RNG per run).
 *
 * <p>Seed is part of the task cache key: it is declared as a task {@code @Input}, so a
 * seed change is a cache miss, not a silent rename drift.
 */
public class BozarNamingExtension {

    private final Property<Long> seed;

    public BozarNamingExtension(ObjectFactory objects) {
        this.seed = objects.property(Long.class);
    }

    public Property<Long> getSeed() { return seed; }

    public void seed(long value) { this.seed.set(value); }
    public void setSeed(long value) { this.seed.set(value); }
}
