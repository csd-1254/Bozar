package io.github.vimasig.bozar.core;

import io.github.vimasig.bozar.obfuscator.Bozar;
import io.github.vimasig.bozar.obfuscator.transformer.impl.ConstantTransformer;
import io.github.vimasig.bozar.obfuscator.transformer.impl.renamer.ClassRenamerTransformer;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the {@code TransformerName:pattern} scoping prefix. This is what lets a build
 * keep names but still strip line numbers, or disable only control flow.
 */
class TransformerScopingTest {

    private static final String KEEP = "com.example.Main";

    private static Bozar with(String exclude) {
        return new Bozar(new BozarConfig("in.jar", "out.jar", exclude, List.of(), options()));
    }

    private static BozarOptions options() {
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
    void scopedRuleAppliesOnlyToTheNamedTransformer() {
        Bozar bozar = with("ClassRenamerTransformer:" + KEEP);
        ClassRenamerTransformer renamer = new ClassRenamerTransformer(bozar);
        ConstantTransformer constant = new ConstantTransformer(bozar);

        assertTrue(bozar.isExcluded(renamer, KEEP), "scoped rule must apply to its own transformer");
        assertFalse(bozar.isExcluded(constant, KEEP), "scoped rule must not leak to other transformers");
    }

    @Test
    void scopedRuleNeverAppliesToGlobalLookups() {
        Bozar bozar = with("ClassRenamerTransformer:" + KEEP);
        assertFalse(bozar.isExcluded(null, KEEP),
                "a scoped rule must not apply to class-wide/resource lookups (classTransformer == null)");
    }

    @Test
    void unknownTransformerNameExcludesNothing() {
        Bozar bozar = with("DoesNotExist:" + KEEP);
        ClassRenamerTransformer renamer = new ClassRenamerTransformer(bozar);
        assertFalse(bozar.isExcluded(renamer, KEEP));
    }

    @Test
    void multipleRulesOfTheSameTransformerAllApply() {
        Bozar bozar = with("ClassRenamerTransformer:" + KEEP + "\nClassRenamerTransformer:com.example.Other");
        ClassRenamerTransformer renamer = new ClassRenamerTransformer(bozar);
        assertTrue(bozar.isExcluded(renamer, KEEP));
        assertTrue(bozar.isExcluded(renamer, "com.example.Other"));
        assertFalse(bozar.isExcluded(renamer, "com.example.Third"));
    }
}
