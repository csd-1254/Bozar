package io.github.vimasig.bozar.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.DefaultTask;

import io.github.vimasig.bozar.core.KeepRules;
import io.github.vimasig.bozar.core.MinecraftMetadata;
import io.github.vimasig.bozar.core.ObfuscationEngine;
import io.github.vimasig.bozar.core.ObfuscationRequest;
import io.github.vimasig.bozar.core.ObfuscationResult;
import io.github.vimasig.bozar.core.Profile;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.ControlFlowObfuscationOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.ConstantObfuscationOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.LineNumberOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.LocalVariableOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.RenameOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.StringObfuscationOption;
import io.github.vimasig.bozar.obfuscator.utils.model.BozarConfig.BozarOptions.WatermarkOptions;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the core engine once and publishes the obfuscated JAR.
 *
 * <p>Cache-correct: every value that can change the output bytes is declared as an
 * {@code @Input}, so the seed, the profile, the keep rules and the classpath are all part
 * of the cache key. The input JAR is fingerprinted by content with a content-only location
 * policy, so moving the build directory does not invalidate the cache.
 *
 * <p>Configuration-cache clean: no {@code Project}, no configuration container access and
 * no filesystem walk of anything but the declared input. All policy is resolved to plain
 * properties by {@link BozarExtension} at configuration time.
 *
 * <p>Metadata is read in {@link #obfuscate()} rather than at configuration time on purpose:
 * it is part of the input JAR, so reading it during configuration would be an eager file
 * read that both the incremental and configuration-cache models reject.
 */
@CacheableTask
public abstract class ObfuscateTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @OutputFile
    @Optional
    public abstract RegularFileProperty getMappingOutput();

    @Input
    public abstract Property<String> getProfileName();

    /** Explicit toggles; absent means the profile decides. */
    @Input
    @Optional
    public abstract Property<Boolean> getRenamerEnabled();

    @Input
    @Optional
    public abstract Property<String> getRenamerLevel();

    @Input
    @Optional
    public abstract Property<Boolean> getControlFlowEnabled();

    @Input
    @Optional
    public abstract Property<String> getControlFlowLevel();

    @Input
    @Optional
    public abstract Property<Boolean> getConstantsEnabled();

    @Input
    @Optional
    public abstract Property<String> getConstantsLevel();

    @Input
    @Optional
    public abstract Property<String> getStringLevel();

    @Input
    @Optional
    public abstract Property<Boolean> getRemoveSourceFile();

    @Input
    @Optional
    public abstract Property<String> getDebugLineNumbersLevel();

    @Input
    @Optional
    public abstract Property<String> getDebugLocalVariablesLevel();

    @Input
    @Optional
    public abstract Property<Boolean> getShuffle();

    @Input
    @Optional
    public abstract Property<Boolean> getRemoveInnerClasses();

    @Input
    @Optional
    public abstract Property<Boolean> getCrasher();

    @Input
    @Optional
    public abstract Property<Boolean> getWatermarkDummyClass();

    @Input
    @Optional
    public abstract Property<Boolean> getWatermarkTextInsideClass();

    @Input
    @Optional
    public abstract Property<Boolean> getWatermarkLdcPop();

    @Input
    @Optional
    public abstract Property<Boolean> getWatermarkZipComment();

    @Input
    @Optional
    public abstract Property<String> getWatermarkDummyClassText();

    @Input
    @Optional
    public abstract Property<String> getWatermarkTextInsideClassText();

    @Input
    @Optional
    public abstract Property<String> getWatermarkLdcPopText();

    @Input
    @Optional
    public abstract Property<String> getWatermarkZipCommentText();

    @Input
    @Optional
    public abstract Property<Long> getSeed();

    @Input
    public abstract Property<Boolean> getVerify();

    @Input
    public abstract Property<Boolean> getMinecraftEnabled();

    /**
     * The task's entrypoint policy. {@code null} (never set) means "use the extension's
     * value"; an explicit value overrides the extension, which is what the
     * {@code obfuscate { entrypointMode = ... }} task-level DSL needs.
     */
    @Input
    @Optional
    public abstract Property<EntrypointMode> getEntrypointMode();

    @Input
    public abstract Property<Boolean> getKeepServiceProviders();

    /** User keep rules as raw patterns (no transformer prefix); see {@link KeepRules}. */
    @Input
    public abstract ListProperty<String> getKeepPatterns();

    /** Patterns excluded from EVERY transformer (copied through verbatim); see {@link KeepRules#excludeAll}. */
    @Input
    public abstract ListProperty<String> getExcludePatterns();

    /** Extra classpath JARs the engine's remapper must be able to resolve. */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    @Optional
    public abstract ConfigurableFileCollection getClasspath();

    @TaskAction
    public void obfuscate() {
        final Path input = getInputJar().get().getAsFile().toPath();
        final Path output = getOutputJar().get().getAsFile().toPath();

        final List<String> libraries = new ArrayList<>();
        for (File library : getClasspath().getFiles()) {
            libraries.add(library.getAbsolutePath());
        }

        ObfuscationResult result = ObfuscationEngine.execute(ObfuscationRequest.builder()
                .inputJar(input)
                .outputJar(output)
                .classpath(libraries)
                .exclude(buildExclude(input))
                .options(resolveOptions())
                .verify(getVerify().getOrElse(true))
                .seed(getSeed().getOrNull())
                .mappingOutput(getMappingOutput().getOrNull() != null
                        ? getMappingOutput().get().getAsFile().toPath() : null)
                .build());

        getLogger().lifecycle("Bozar: {} -> {} ({} -> {})",
                result.getInputJar(), result.getOutputJar(),
                compact(result.getInputSize()), compact(result.getOutputSize()));
        if (result.getMappingOutput() != null) {
            getLogger().lifecycle("Bozar: rename mapping written to {}", result.getMappingOutput());
        }
    }

    /**
     * Combines the user keep rules with the Minecraft-derived ones into
     * {@code ClassRenamerTransformer:pattern} lines.
     */
    private String buildExclude(Path input) {
        KeepRules rules = KeepRules.create();
        getKeepPatterns().getOrElse(List.of()).forEach(rules::className);
        getExcludePatterns().getOrElse(List.of()).forEach(rules::excludeAll);

        if (!getMinecraftEnabled().getOrElse(false)) {
            return render(rules);
        }

        try {
            final List<String> entrypoints = MinecraftMetadata.entrypoints(input);
            getLogger().info("Bozar: plugin entrypoint(s) {}", entrypoints);
            if (getEntrypointMode().getOrElse(EntrypointMode.KEEP).equals(EntrypointMode.KEEP)) {
                rules.entrypoints(entrypoints);
            }
            if (getKeepServiceProviders().getOrElse(true)) {
                final List<String> providers = MinecraftMetadata.serviceProviders(input);
                if (!providers.isEmpty()) {
                    getLogger().info("Bozar: keeping {} service provider class(es)", providers.size());
                    rules.serviceProviders(providers);
                }
            }
        } catch (Exception e) {
            // Never fail the build because the metadata could not be read; the keep rules
            // are an optimization, and renaming an unread entrypoint is recoverable.
            getLogger().warn("Bozar: could not read Minecraft metadata ({}); "
                    + "continuing without metadata-derived keep rules", e.getMessage());
        }

        return render(rules);
    }

    private static String render(KeepRules rules) {
        final StringBuilder sb = new StringBuilder();
        for (String line : rules.lines()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * Level (explicit enum string) wins over the enabled flag, which wins over the profile. This
     * lets the DSL express "off", "light", "heavy" as a single declaration while staying
     * backward compatible with the boolean {@code enabled} toggle.
     */
    private static <E extends Enum<E>> E resolveEnum(Property<String> level, Property<Boolean> enabled,
                                                     Class<E> type, E profile, E off, E on) {
        final String raw = level.getOrNull();
        if (raw != null && !raw.isEmpty()) {
            return Enum.valueOf(type, raw.toUpperCase());
        }
        if (enabled == null) {
            return profile;  // e.g. strings: only level/profile apply, no boolean toggle.
        }
        final Boolean flag = enabled.getOrNull();
        return flag == null ? profile : (flag ? on : off);
    }

    /** Profile first, then the explicit toggles win. Absent toggles inherit the profile. */
    private BozarOptions resolveOptions() {
        final BozarOptions base = Profile.valueOf(getProfileName().getOrElse(Profile.BASIC.name())).options();

        final RenameOption rename = resolveEnum(getRenamerLevel(), getRenamerEnabled(),
                RenameOption.class, base.getRename(), RenameOption.OFF, RenameOption.ALPHABET);
        final ControlFlowObfuscationOption controlFlow = resolveEnum(getControlFlowLevel(),
                getControlFlowEnabled(), ControlFlowObfuscationOption.class,
                base.getControlFlowObfuscation(), ControlFlowObfuscationOption.OFF,
                ControlFlowObfuscationOption.LIGHT);
        final ConstantObfuscationOption constants = resolveEnum(getConstantsLevel(),
                getConstantsEnabled(), ConstantObfuscationOption.class,
                base.getConstantObfuscation(), ConstantObfuscationOption.OFF,
                ConstantObfuscationOption.LIGHT);
        final StringObfuscationOption strings = resolveEnum(getStringLevel(), null,
                StringObfuscationOption.class, base.getStringObfuscation(),
                StringObfuscationOption.OFF, StringObfuscationOption.LIGHT);

        final boolean removeSourceFile = getRemoveSourceFile().getOrNull() != null
                ? getRemoveSourceFile().get() : base.isRemoveSourceFile();
        final LineNumberOption lineNumbers = resolveEnum(getDebugLineNumbersLevel(), null,
                LineNumberOption.class, base.getLineNumbers(), LineNumberOption.DELETE, LineNumberOption.DELETE);
        final LocalVariableOption localVariables = resolveEnum(getDebugLocalVariablesLevel(), null,
                LocalVariableOption.class, base.getLocalVariables(), LocalVariableOption.DELETE, LocalVariableOption.OBFUSCATE);

        final WatermarkOptions watermark = resolveWatermark(base.getWatermarkOptions());
        final boolean shuffle = getShuffle().getOrNull() != null ? getShuffle().get() : base.isShuffle();
        final boolean removeInnerClasses = getRemoveInnerClasses().getOrNull() != null
                ? getRemoveInnerClasses().get() : base.isRemoveInnerClasses();
        final boolean crasher = getCrasher().getOrNull() != null ? getCrasher().get() : base.isCrasher();

        return new BozarOptions(
                rename,
                lineNumbers,
                localVariables,
                removeSourceFile,
                shuffle, removeInnerClasses,
                controlFlow, crasher, constants, strings, watermark);
    }

    private static String compact(long bytes) {
        if (bytes < 1024) return bytes + "B";
        return String.format("%.1fK", bytes / 1024.0);
    }

    /** Merges watermark overrides from the extension onto the profile defaults. */
    private WatermarkOptions resolveWatermark(WatermarkOptions base) {
        // Watermark options are read directly off the running task's properties (set in BozarExtension.wire).
        final boolean dummyClass = getWatermarkDummyClass().getOrElse(base.isDummyClass());
        final boolean textInsideClass = getWatermarkTextInsideClass().getOrElse(base.isTextInsideClass());
        final boolean ldcPop = getWatermarkLdcPop().getOrElse(base.isLdcPop());
        final boolean zipComment = getWatermarkZipComment().getOrElse(base.isZipComment());
        return new WatermarkOptions(
                dummyClass, textInsideClass, ldcPop, zipComment,
                getWatermarkDummyClassText().getOrElse(base.getDummyClassText()),
                getWatermarkTextInsideClassText().getOrElse(base.getTextInsideClassText()),
                getWatermarkLdcPopText().getOrElse(base.getLdcPopText()),
                getWatermarkZipCommentText().getOrElse(base.getZipCommentText()));
    }

    /** Entrypoint strategy per docs/04 section 3. {@link #KEEP} is the documented default. */
    public enum EntrypointMode {
        /** Do not rename the main class, so the metadata stays correct without rewriting. */
        KEEP,
        /** Rename the main class and rewrite {@code plugin.yml}/{@code paper-plugin.yml}. */
        REWRITE
    }
}
