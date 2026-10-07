package io.github.vimasig.bozar.core;

import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.ControlFlowObfuscationOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.ConstantObfuscationOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.LineNumberOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.LocalVariableOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.RenameOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.StringObfuscationOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.WatermarkOptions;

/**
 * Named obfuscation profiles. Reliability over transformation count: the default
 * enables renaming only, because every other upstream transformer is either untested
 * against Minecraft bytecode or demonstrably fragile.
 *
 * <p>Defaults intentionally leave out the upstream "everything on" combination. Each
 * tier is a plain constructor call; there is no profile inheritance machinery.
 */
public enum Profile {

    /** No transformers enabled. The JAR is copied through unchanged apart from resource rewriting. */
    NONE {
        @Override
        public BozarOptions options() {
            return new BozarOptions(
                    RenameOption.OFF, LineNumberOption.KEEP, LocalVariableOption.KEEP,
                    false, false, false,
                    ControlFlowObfuscationOption.OFF, false,
                    ConstantObfuscationOption.OFF, StringObfuscationOption.OFF,
                    watermarks(false, false, false, false));
        }
    },

    /**
     * Rename classes, fields and methods. No control flow, string, constant or debug
     * obfuscation. The documented default.
     */
    BASIC {
        @Override
        public BozarOptions options() {
            return new BozarOptions(
                    RenameOption.ALPHABET, LineNumberOption.KEEP, LocalVariableOption.KEEP,
                    false, false, false,
                    ControlFlowObfuscationOption.OFF, false,
                    ConstantObfuscationOption.OFF, StringObfuscationOption.OFF,
                    watermarks(false, false, false, false));
        }
    },

    /**
     * BASIC plus debug-symbol removal, light string/constant obfuscation and light
     * control flow.
     */
    STANDARD {
        @Override
        public BozarOptions options() {
            return new BozarOptions(
                    RenameOption.ALPHABET, LineNumberOption.DELETE, LocalVariableOption.OBFUSCATE,
                    true, false, false,
                    ControlFlowObfuscationOption.LIGHT, false,
                    ConstantObfuscationOption.LIGHT, StringObfuscationOption.LIGHT,
                    watermarks(false, false, false, false));
        }
    },

    /**
     * STANDARD plus heavy control flow, flow constants, heavy strings, shuffling,
     * inner-class removal and the full watermark suite. Fragile: verify against a
     * representative plugin before shipping.
     */
    AGGRESSIVE {
        @Override
        public BozarOptions options() {
            return new BozarOptions(
                    RenameOption.INVISIBLE, LineNumberOption.RANDOMIZE, LocalVariableOption.OBFUSCATE,
                    true, true, true,
                    ControlFlowObfuscationOption.HEAVY, false,
                    ConstantObfuscationOption.FLOW, StringObfuscationOption.HEAVY,
                    watermarks(true, true, true, true));
        }
    };

    public abstract BozarOptions options();

    private static WatermarkOptions watermarks(boolean dummyClass, boolean textInsideClass,
                                               boolean ldcPop, boolean zipComment) {
        return new WatermarkOptions(dummyClass, textInsideClass, ldcPop, zipComment,
                "Obfuscated with Bozar", "Obfuscated with Bozar", "Obfuscated with Bozar",
                "Obfuscated with Bozar");
    }
}
