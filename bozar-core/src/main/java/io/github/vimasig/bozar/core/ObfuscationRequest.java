package io.github.vimasig.bozar.core;

import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * Plain-Java configuration for a single obfuscation run. Carries no Gradle types.
 *
 * <p>Mirrors {@link BozarConfig} but is built from immutable Java objects so that
 * it can cross the module boundary into the Gradle plugin module unchanged.
 */
public final class ObfuscationRequest {

    private final Path inputJar;
    private final Path outputJar;
    private final List<String> classpath;
    private final String exclude;
    private final BozarConfig.BozarOptions options;
    private final boolean verify;
    private final Long seed;
    private final Path mappingOutput;

    private ObfuscationRequest(Builder b) {
        this.inputJar = b.inputJar;
        this.outputJar = b.outputJar;
        this.classpath = List.copyOf(b.classpath);
        this.exclude = b.exclude;
        this.options = b.options;
        this.verify = b.verify;
        this.seed = b.seed;
        this.mappingOutput = b.mappingOutput;
    }

    public Path getInputJar() {
        return this.inputJar;
    }

    public Path getOutputJar() {
        return this.outputJar;
    }

    public List<String> getClasspath() {
        return this.classpath;
    }

    public String getExclude() {
        return this.exclude;
    }

    public BozarConfig.BozarOptions getOptions() {
        return this.options;
    }

    public boolean isVerify() {
        return this.verify;
    }

    /**
     * Optional RNG seed for reproducible builds. {@code null} keeps upstream's
     * unseeded behavior. Must be part of the task's inputs if the task is cached.
     */
    public Long getSeed() {
        return this.seed;
    }

    /**
     * Optional path where the {@code oldName -> newName} mapping file should be
     * written. {@code null} means no mapping file is produced.
     */
    public Path getMappingOutput() {
        return this.mappingOutput;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path inputJar;
        private Path outputJar;
        private List<String> classpath = List.of();
        private String exclude = "";
        private BozarConfig.BozarOptions options;
        private boolean verify = true;
        private Long seed;
        private Path mappingOutput;

        public Builder inputJar(Path inputJar) {
            this.inputJar = inputJar;
            return this;
        }

        public Builder outputJar(Path outputJar) {
            this.outputJar = outputJar;
            return this;
        }

        public Builder classpath(List<String> classpath) {
            this.classpath = classpath;
            return this;
        }

        public Builder exclude(String exclude) {
            this.exclude = exclude;
            return this;
        }

        public Builder options(BozarConfig.BozarOptions options) {
            this.options = options;
            return this;
        }

        public Builder verify(boolean verify) {
            this.verify = verify;
            return this;
        }

        public Builder seed(Long seed) {
            this.seed = seed;
            return this;
        }

        public Builder mappingOutput(Path mappingOutput) {
            this.mappingOutput = mappingOutput;
            return this;
        }

        public ObfuscationRequest build() {
            if (this.inputJar == null) throw new IllegalStateException("inputJar is required");
            if (this.outputJar == null) throw new IllegalStateException("outputJar is required");
            if (this.options == null) throw new IllegalStateException("options are required");
            return new ObfuscationRequest(this);
        }
    }
}
