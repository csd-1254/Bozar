package io.github.vimasig.bozar.core;

import java.nio.file.Path;

/**
 * Result of a successful obfuscation run.
 */
public final class ObfuscationResult {

    private final Path inputJar;
    private final Path outputJar;
    private final Path mappingOutput;
    private final long inputSize;
    private final long outputSize;

    public ObfuscationResult(Path inputJar, Path outputJar, Path mappingOutput, long inputSize, long outputSize) {
        this.inputJar = inputJar;
        this.outputJar = outputJar;
        this.mappingOutput = mappingOutput;
        this.inputSize = inputSize;
        this.outputSize = outputSize;
    }

    public Path getInputJar() {
        return this.inputJar;
    }

    public Path getOutputJar() {
        return this.outputJar;
    }

    public Path getMappingOutput() {
        return this.mappingOutput;
    }

    public long getInputSize() {
        return this.inputSize;
    }

    public long getOutputSize() {
        return this.outputSize;
    }
}
