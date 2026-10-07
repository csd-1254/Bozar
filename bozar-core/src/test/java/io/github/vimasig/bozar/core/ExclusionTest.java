package io.github.vimasig.bozar.core;

import io.github.vimasig.bozar.obfuscator.Bozar;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the exclusion pattern language in {@link Bozar#isExcluded}. Every pattern form
 * here is load-bearing for Bukkit/Paper keep rules, so a regression here breaks plugin
 * entrypoints silently rather than loudly.
 */
class ExclusionTest {

    private static Bozar with(String exclude) {
        return new Bozar(new BozarConfig("in.jar", "out.jar", exclude, List.of(), disabledOptions()));
    }

    /** All options off: no transformer is enabled, so {@code isExcluded} is the only logic under test. */
    private static BozarOptions disabledOptions() {
        return new BozarOptions(
                BozarOptions.RenameOption.OFF,
                BozarOptions.LineNumberOption.KEEP,
                BozarOptions.LocalVariableOption.KEEP,
                false, false, false,
                BozarOptions.ControlFlowObfuscationOption.OFF,
                false,
                BozarOptions.ConstantObfuscationOption.OFF,
                BozarOptions.StringObfuscationOption.OFF,
                new BozarOptions.WatermarkOptions(false, false, false, false, null, null, null, null));
    }

    @Test
    void blankLinesAndCommentsNeverExclude() {
        Bozar bozar = with("# comment\n\n   \n");
        assertFalse(bozar.isExcluded(null, "com.example.Main"));
    }

    @Test
    void exactMatchExcludes() {
        Bozar bozar = with("com.example.Main");
        assertTrue(bozar.isExcluded(null, "com.example.Main"));
        assertFalse(bozar.isExcluded(null, "com.example.Main.Other"));
    }

    @Test
    void innerClassReferenceIsNormalizedToTheOuterClass() {
        Bozar bozar = with("com.example.Main");
        assertTrue(bozar.isExcluded(null, "com.example.Main$Inner"));
    }

    @Test
    void leadingDoubleStarMatchesAnyPrefix() {
        Bozar bozar = with("**.Main");
        assertTrue(bozar.isExcluded(null, "com.example.Main"));
        assertFalse(bozar.isExcluded(null, "Main"),
                "leading ** is a pure suffix match: a pattern with no leading characters matches every name, so use the bare name instead");
        assertFalse(bozar.isExcluded(null, "com.example.MainExtra"));
    }

    @Test
    void leadingSingleStarMatchesSuffix() {
        Bozar bozar = with("*.service");
        assertTrue(bozar.isExcluded(null, "com.example.service"));
        assertFalse(bozar.isExcluded(null, "com.example.serviceX"));
    }

    @Test
    void trailingDoubleStarMatchesAnySubtree() {
        Bozar bozar = with("com.example.api.**");
        assertTrue(bozar.isExcluded(null, "com.example.api.Foo"));
        assertTrue(bozar.isExcluded(null, "com.example.api.deep.Foo"));
        assertFalse(bozar.isExcluded(null, "com.example.app.Foo"));
    }

    @Test
    void trailingSingleStarRequiresTheSameDepth() {
        Bozar bozar = with("com.example.*");
        assertTrue(bozar.isExcluded(null, "com.example.Foo"));
        // same dot count as the pattern, so this is NOT excluded
        assertFalse(bozar.isExcluded(null, "com.example.api.Foo"));
    }

    @Test
    void blankAndCommentPatternsBetweenRealPatternsAreIgnored() {
        Bozar bozar = with("com.example.Keep\n# noise\n\ncom.example.Drop");
        assertTrue(bozar.isExcluded(null, "com.example.Keep"));
        assertTrue(bozar.isExcluded(null, "com.example.Drop"));
    }
}
