package io.github.vimasig.bozar.obfuscator.utils.model;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

public class BozarConfig {

    private transient final File input;
    private transient final Path output;
    private final String exclude;
    private final List<String> libraries;
    private final BozarOptions options;

    public BozarConfig(String input, String output, String exclude, List<String> libraries, BozarOptions bozarOptions) {
        this.input = new File(input);
        this.output = Path.of(output);
        this.exclude = exclude;
        this.libraries = libraries;
        this.options = bozarOptions;
    }

    public File getInput() {
        return this.input;
    }

    public Path getOutput() {
        return this.output;
    }

    public String getExclude() {
        return this.exclude;
    }

    public List<String> getLibraries() {
        return this.libraries;
    }

    public BozarOptions getOptions() {
        return this.options;
    }

    public static class BozarOptions {
        public enum RenameOption {
            OFF, ALPHABET, INVISIBLE, IlIlIlIlIl,
        }

        public enum LineNumberOption {
            KEEP, DELETE, RANDOMIZE
        }

        public enum LocalVariableOption {
            KEEP, DELETE, OBFUSCATE
        }

        public enum ControlFlowObfuscationOption {
            OFF, LIGHT, HEAVY
        }

        public enum ConstantObfuscationOption {
            OFF, LIGHT, FLOW
        }

        public enum StringObfuscationOption {
            OFF, LIGHT, HEAVY
        }

        // Obfuscation options
        private final RenameOption rename;
        private final LineNumberOption lineNumbers;
        private final LocalVariableOption localVariables;
        private final boolean removeSourceFile;
        private final boolean shuffle;
        private final boolean removeInnerClasses;
        private final ControlFlowObfuscationOption controlFlowObfuscation;
        private final boolean crasher;
        private final ConstantObfuscationOption constantObfuscation;
        private final StringObfuscationOption stringObfuscation;
        private final WatermarkOptions watermarkOptions;

        public BozarOptions(RenameOption rename, LineNumberOption lineNumbers, LocalVariableOption localVariables,
                            boolean removeSourceFile, boolean shuffle, boolean removeInnerClasses,
                            ControlFlowObfuscationOption controlFlowObfuscation, boolean crasher,
                            ConstantObfuscationOption constantObfuscation, StringObfuscationOption stringObfuscation,
                            WatermarkOptions watermarkOptions) {
            this.rename = rename;
            this.lineNumbers = lineNumbers;
            this.localVariables = localVariables;
            this.removeSourceFile = removeSourceFile;
            this.shuffle = shuffle;
            this.removeInnerClasses = removeInnerClasses;
            this.controlFlowObfuscation = controlFlowObfuscation;
            this.crasher = crasher;
            this.constantObfuscation = constantObfuscation;
            this.stringObfuscation = stringObfuscation;
            this.watermarkOptions = watermarkOptions;
        }

        public RenameOption getRename() {
            return this.rename;
        }

        public LineNumberOption getLineNumbers() {
            return this.lineNumbers;
        }

        public LocalVariableOption getLocalVariables() {
            return this.localVariables;
        }

        public boolean isRemoveSourceFile() {
            return this.removeSourceFile;
        }

        public boolean isShuffle() {
            return this.shuffle;
        }

        public boolean isRemoveInnerClasses() {
            return this.removeInnerClasses;
        }

        public ControlFlowObfuscationOption getControlFlowObfuscation() {
            return this.controlFlowObfuscation;
        }

        public boolean isCrasher() {
            return this.crasher;
        }

        public ConstantObfuscationOption getConstantObfuscation() {
            return this.constantObfuscation;
        }

        public StringObfuscationOption getStringObfuscation() {
            return this.stringObfuscation;
        }

        public WatermarkOptions getWatermarkOptions() {
            return this.watermarkOptions;
        }

        public static class WatermarkOptions {
            private final boolean dummyClass;
            private final boolean textInsideClass;
            private final boolean ldcPop;
            private final boolean zipComment;
            private final String dummyClassText;
            private final String textInsideClassText;
            private final String ldcPopText;
            private final String zipCommentText;

            public WatermarkOptions(boolean dummyClass, boolean textInsideClass, boolean ldcPop, boolean zipComment,
                                    String dummyClassText, String textInsideClassText, String ldcPopText,
                                    String zipCommentText) {
                this.dummyClass = dummyClass;
                this.textInsideClass = textInsideClass;
                this.ldcPop = ldcPop;
                this.zipComment = zipComment;
                this.dummyClassText = dummyClassText;
                this.textInsideClassText = textInsideClassText;
                this.ldcPopText = ldcPopText;
                this.zipCommentText = zipCommentText;
            }

            public boolean isDummyClass() {
                return this.dummyClass;
            }

            public boolean isTextInsideClass() {
                return this.textInsideClass;
            }

            public boolean isLdcPop() {
                return this.ldcPop;
            }

            public boolean isZipComment() {
                return this.zipComment;
            }

            public String getDummyClassText() {
                return this.dummyClassText;
            }

            public String getTextInsideClassText() {
                return this.textInsideClassText;
            }

            public String getLdcPopText() {
                return this.ldcPopText;
            }

            public String getZipCommentText() {
                return this.zipCommentText;
            }
        }
    }

    public static record EnableType(Supplier<Boolean> isEnabled, Object type) { }
}
