package io.github.vimasig.bozar.core;

import java.util.Random;

/**
 * Optional RNG seed so a build's output <em>content</em> is reproducible.
 *
 * <p>Upstream constructs a fresh unseeded {@link Random} per transformer, so identical
 * builds produce different output every run. This indirection is the only change needed
 * in the engine to support the documented {@code naming.seed} option; the default keeps
 * upstream's unseeded behavior.
 *
 * <p>This reproduces content, not bytes: the engine stamps ZIP entries with the wall
 * clock, and some upstream transformers read {@code ThreadLocalRandom} directly, bypassing
 * this seed. See docs/12 sections 1.4 and 2.6.
 */
public final class Randoms {

    private static final ThreadLocal<Long> SEED = new ThreadLocal<>();

    private Randoms() {
    }

    /** Seed for the current run, or {@code null} to keep upstream's unseeded RNG. */
    public static void setSeed(Long seed) {
        if (seed == null) {
            SEED.remove();
        } else {
            SEED.set(seed);
        }
    }

    public static Long getSeed() {
        return SEED.get();
    }

    /** A transformer-level RNG: seeded when {@link #setSeed} was called, unseeded otherwise. */
    public static Random newRandom() {
        Long seed = SEED.get();
        return seed == null ? new Random() : new Random(seed);
    }
}
