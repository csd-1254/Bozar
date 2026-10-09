package io.github.vimasig.bozar.gradle;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

import java.util.ArrayList;

/**
 * The {@code bozar { }} DSL. Every value is a lazy {@link Property}; nothing about
 * {@code Project} state is resolved at construction time except the layout, so the extension
 * is configuration-cache safe. The task is wired once, in {@link #wire(Project)}, inside
 * {@code afterEvaluate} so a user's {@code bozar { }} block has already been applied.
 *
 * <p>Section objects are memoized: the DSL action and the task wiring must observe the same
 * instance, otherwise a value set in the DSL never reaches the task.
 *
 * <p>Default profile is {@code BASIC} — rename only. Every other upstream transformer is
 * either untested against Minecraft bytecode or demonstrably fragile, so the documented
 * default never enables control flow, strings, constants or watermarks.
 */
public abstract class BozarExtension {

    private static final String JAR_TASK_NAME = "jar";

    private final Project project;
    private final ObjectFactory objects;

    private final Property<Boolean> enabled;
    private final Property<String> profileName;
    private final Property<Boolean> verify;
    private final Property<Long> seed;
    private final Property<Boolean> mappingEnabled;
    private final Property<ObfuscateTask.EntrypointMode> entrypointMode;
    private final Property<Boolean> keepServiceProviders;
    private final Property<Boolean> autoCollectDependencies;
    private final Property<Boolean> shuffle;
    private final Property<Boolean> removeInnerClasses;
    private final Property<Boolean> crasher;
    private final RegularFileProperty inputJar;
    private final DirectoryProperty dependenciesDir;
    private final RegularFileProperty outputJar;
    private final RegularFileProperty mappingOutput;
    private final ListProperty<String> keepPatterns;
    private final ListProperty<String> excludePatterns;
    private final ConfigurableFileCollection classpath;

    private BozarMinecraftExtension minecraft;
    private BozarOptionExtension renamerSection;
    private BozarOptionExtension controlFlowSection;
    private BozarOptionExtension constantsSection;
    private BozarOptionExtension stringsSection;
    private BozarDebugExtension debugSection;
    private BozarNamingExtension namingSection;
    private BozarWatermarkExtension watermarkSection;

    public BozarExtension(Project project) {
        this.project = project;
        this.objects = project.getObjects();

        this.enabled = prop(Boolean.class, true);
        this.profileName = prop(String.class, "BASIC");
        this.verify = prop(Boolean.class, true);
        this.seed = this.objects.property(Long.class);
        this.mappingEnabled = prop(Boolean.class, true);
        this.entrypointMode = prop(ObfuscateTask.EntrypointMode.class,
                ObfuscateTask.EntrypointMode.KEEP);
        this.keepServiceProviders = prop(Boolean.class, true);
        this.autoCollectDependencies = prop(Boolean.class, true);

        this.inputJar = this.objects.fileProperty();
        this.outputJar = this.objects.fileProperty();
        this.mappingOutput = this.objects.fileProperty();
        this.dependenciesDir = this.objects.directoryProperty();
        this.keepPatterns = this.objects.listProperty(String.class).convention(new ArrayList<>());
        this.excludePatterns = this.objects.listProperty(String.class).convention(new ArrayList<>());
        this.classpath = project.files();

        this.shuffle = prop(Boolean.class, null);
        this.removeInnerClasses = prop(Boolean.class, null);
        this.crasher = prop(Boolean.class, null);

        // Defaults are providers: the jar task is only configured when an output is needed.
        final Provider<Jar> jarTask = jarTaskProvider(project);
        this.inputJar.convention(jarTask.flatMap(Jar::getArchiveFile));
        this.outputJar.convention(project.getLayout().getBuildDirectory().file(
                project.provider(() -> "libs/" + project.getName() + "-obfuscated.jar")));
        this.mappingOutput.convention(project.getLayout().getBuildDirectory().file(
                "bozar/mapping.txt"));
        this.dependenciesDir.convention(project.getLayout().getBuildDirectory().dir(
                "bozar/dependencies"));
    }

    private Provider<Jar> jarTaskProvider(Project project) {
        return project.getTasks().named(JAR_TASK_NAME).map(t -> (Jar) t);
    }

    private <T> Property<T> prop(Class<T> type, T value) {
        return this.objects.property(type).convention(value);
    }

    /**
     * Wire the {@code obfuscate} task. Called once from the plugin at apply time.
     *
     * <p>No {@code afterEvaluate} and no eager property reads: the task is registered lazily
     * and every value is copied by provider, so a value the user sets in {@code bozar { }}
     * after this method runs is still the value the task sees at execution time.
     */
    public void wire(Project project) {
        final Provider<Jar> jarTask = jarTaskProvider(project);

        // Configuration-cache requirement: anything the task retains must not reach `Project`
        // through a captured `this`. These locals hold only Gradle provider types.
        final Property<Boolean> enabledFlag = this.enabled;
        final Property<Boolean> mappingFlag = this.mappingEnabled;
        final RegularFileProperty mappingFile = this.mappingOutput;
        final ProjectLayout layout = project.getLayout();
        final ProviderFactory providers = project.getProviders();

        // Lazy on purpose: the flag may be set after wire() runs, and resolving a configuration
        // during configuration is forbidden by the configuration-cache contract. Only the
        // compileOnly API surface is collected, not the full runtime classpath: Bozar's remapper
        // only needs the Minecraft/Bukkit API types to resolve during renaming, and feeding it
        // transitive implementation deps (WorldGuard, etc.) made the engine's URLClassLoader and
        // the remapper run out of heap on large plugins. The configuration lookup and its
        // non-transitive resolution are deferred entirely into the provider below so nothing
        // touches `compileClasspath` during configuration (which would finalize the toolchain
        // before the consumer's `java { toolchain.languageVersion = ... }` can run).
        final FileCollection collectedDependencies = project.files(providers.provider(() ->
                autoCollectDependencies.get()
                        ? compileOnlyFiles(project)
                        : java.util.List.of()));

        final TaskProvider<ObfuscateTask> obfuscate = project.getTasks()
                .register("obfuscate", ObfuscateTask.class, task -> {
                    task.setDescription("Obfuscates the JAR produced by this project.");
                    task.setGroup("bozar");

                    task.getInputJar().set(inputJar);
                    task.getOutputJar().set(outputJar);
                    // A disabled mapping is an absent value, which the @Optional output accepts.
                    task.getMappingOutput().set(layout.file(providers.provider(() ->
                            mappingFlag.get() ? mappingFile.getAsFile().getOrNull() : null)));
                    task.getProfileName().set(profileName);
                    task.getRenamerEnabled().set(renamer().getEnabled());
                    task.getRenamerLevel().set(renamer().getLevel());
                    task.getControlFlowEnabled().set(controlFlow().getEnabled());
                    task.getControlFlowLevel().set(controlFlow().getLevel());
                    task.getConstantsEnabled().set(constants().getEnabled());
                    task.getConstantsLevel().set(constants().getLevel());
                    task.getStringLevel().set(strings().getLevel());
                    task.getRemoveSourceFile().set(debug().getRemoveSourceFile());
                    task.getDebugLineNumbersLevel().set(debug().getLineNumbers());
                    task.getDebugLocalVariablesLevel().set(debug().getLocalVariables());
                    task.getShuffle().set(shuffle);
                    task.getRemoveInnerClasses().set(removeInnerClasses);
                    task.getCrasher().set(crasher);
                    task.getWatermarkDummyClass().set(watermark().getDummyClass());
                    task.getWatermarkTextInsideClass().set(watermark().getTextInsideClass());
                    task.getWatermarkLdcPop().set(watermark().getLdcPop());
                    task.getWatermarkZipComment().set(watermark().getZipComment());
                    task.getWatermarkDummyClassText().set(watermark().getDummyClassText());
                    task.getWatermarkTextInsideClassText().set(watermark().getTextInsideClassText());
                    task.getWatermarkLdcPopText().set(watermark().getLdcPopText());
                    task.getWatermarkZipCommentText().set(watermark().getZipCommentText());
                    // The section wins over a top-level seed: it is the more specific declaration.
                    task.getSeed().set(naming().getSeed().orElse(seed));
                    task.getVerify().set(verify);
                    task.getMinecraftEnabled().set(minecraft().getEnabled());
                    task.getEntrypointMode().set(entrypointMode);
                    task.getKeepServiceProviders().set(keepServiceProviders);
                    task.getKeepPatterns().set(keepPatterns);
                    task.getExcludePatterns().set(excludePatterns);
                    task.getClasspath().from(classpath);
                    task.getClasspath().from(collectedDependencies);

                    // Skipping rather than not registering keeps `build`'s dependency graph
                    // stable, which is what makes configuration-cache reuse work across both
                    // the enabled and disabled states.
                    task.onlyIf(t -> enabledFlag.getOrElse(true));
                    task.dependsOn(jarTask);
                });

        // Must be outside the registration action: the action runs only when the task is
        // realized, and nothing would realize it, so the edge would never be added.
        project.getTasks().named("build").configure(t -> t.dependsOn(obfuscate));

        // Export the plugin's dependencies to a folder, like Skidfuscator's
        // collectSkidfuscatorDependencies -> build/skidfuscator/dependencies/. from(configuration)
        // wires the producing tasks (e.g. :bukkit-stub:jar) and lazily resolves the full
        // compileClasspath at execution time, so this is configuration-cache safe and the
        // destination only receives files that currently exist.
        final org.gradle.api.artifacts.Configuration compileClasspathCfg =
                project.getConfigurations().findByName("compileClasspath");
        project.getTasks().register("collectBozarDependencies", org.gradle.api.tasks.Sync.class, t -> {
            t.setDescription("Copies the plugin's compile dependencies into the bozar dependencies folder.");
            t.setGroup("bozar");
            t.into(dependenciesDir);
            if (compileClasspathCfg != null) {
                t.from(compileClasspathCfg);
            }
        });
    }

    /** Direct compile-only API jars of the main source set — the server-runtime API surface
     *  (Bukkit/Paper) the remapper needs to resolve types during renaming. Only *direct*
     *  deps are taken, never their transitive closure: Paper's `compileClasspath` expands to
     *  30+ jars (Guava, GSON, SnakeYAML, BungeeCord, Maven resolver, ...), none of which appear
     *  in a plugin's bytecode and all of which the engine would load into its URLClassLoader —
     *  that is what pushed the heap over the edge on large plugins (the previous implementation
     *  collected the full runtime classpath, transitively, and the JVM OOM-killed the build).
     *
     *  Resolved at execution time inside the provider so it never runs at configuration time
     *  (which would finalize the consumer's toolchain before its `java { toolchain... }` line).
     *  Uses the `LenientConfiguration` API rather than `Configuration.copy().setTransitive(false)`
     *  because the latter does not resolve project component variants to their output jars on
     *  Gradle 8.x. */
    private java.util.List<java.io.File> compileOnlyFiles(Project project) {
        final org.gradle.api.artifacts.Configuration compileClasspath =
                project.getConfigurations().findByName("compileClasspath");
        if (compileClasspath == null) {
            return java.util.List.of();
        }

        // Configuration-cache safe: resolution happens at execution time inside the provider.
        // We need direct dependencies only (Bukkit/Paper API jars) so the remapper can resolve
        // types during renaming, without the 30+ transitive jars (Guava, SnakeYAML, ...) that
        // caused OOM on large plugins.
        //
        // Configuration.copy() + setTransitive(false) does not resolve project component
        // variants on Gradle 8.x (returns 0 files for project deps). Instead we use the
        // LenientConfiguration API which properly resolves project dependencies to their
        // output artifacts. getFirstLevelModuleDependencies() returns only direct deps;
        // getModuleArtifacts() gives the jar files (including the built stub jar for a
        // project dependency).
        final org.gradle.api.artifacts.LenientConfiguration lenient =
                compileClasspath.getResolvedConfiguration().getLenientConfiguration();
        final java.util.ArrayList<java.io.File> files = new java.util.ArrayList<>();
        for (org.gradle.api.artifacts.ResolvedDependency dep : lenient.getFirstLevelModuleDependencies()) {
            for (org.gradle.api.artifacts.ResolvedArtifact art : dep.getModuleArtifacts()) {
                files.add(art.getFile());
            }
        }
        return files;
    }

    // ------------------------------------------------------------------ DSL

    public Property<Boolean> getEnabled() { return enabled; }
    public Property<String> getProfileName() { return profileName; }
    public Property<Boolean> getVerify() { return verify; }
    public Property<Long> getSeed() { return seed; }
    public Property<Boolean> getMappingEnabled() { return mappingEnabled; }
    public RegularFileProperty getInputJar() { return inputJar; }
    public RegularFileProperty getOutputJar() { return outputJar; }
    public RegularFileProperty getMappingOutput() { return mappingOutput; }
    public ListProperty<String> getKeepPatterns() { return keepPatterns; }
    public ListProperty<String> getExcludePatterns() { return excludePatterns; }
    public ConfigurableFileCollection getClasspath() { return classpath; }
    public DirectoryProperty getDependenciesDir() { return dependenciesDir; }
    public Property<Boolean> getKeepServiceProviders() { return keepServiceProviders; }
    public Property<Boolean> getAutoCollectDependencies() { return autoCollectDependencies; }
    public Property<ObfuscateTask.EntrypointMode> getEntrypointMode() { return entrypointMode; }

    public Property<Boolean> getShuffle() { return shuffle; }
    public Property<Boolean> getRemoveInnerClasses() { return removeInnerClasses; }
    public Property<Boolean> getCrasher() { return crasher; }

    public void keep(String className) {
        keepPatterns.add(className.trim());
    }

    public void keepPackage(String packageName) {
        final String name = packageName.trim();
        keepPatterns.add(name.endsWith(".**") ? name : name + ".**");
    }

    /** Exclude a class from EVERY transformer (rename, constants, strings, debug, ...), not just
     *  renaming. The class is copied through unchanged. Dot-separated name, {@code *}/{@code **} ok. */
    public void exclude(String className) {
        excludePatterns.add(className.trim());
    }

    /** Exclude an entire package from EVERY transformer. {@code com.example} becomes {@code com.example.**}. */
    public void excludePackage(String packageName) {
        final String name = packageName.trim();
        excludePatterns.add(name.endsWith(".**") ? name : name + ".**");
    }

    public void minecraft(Action<BozarMinecraftExtension> action) { action.execute(minecraft()); }
    public void renamer(Action<BozarOptionExtension> action) { action.execute(renamer()); }
    public void controlFlow(Action<BozarOptionExtension> action) { action.execute(controlFlow()); }
    public void constants(Action<BozarOptionExtension> action) { action.execute(constants()); }
    public void debug(Action<BozarDebugExtension> action) { action.execute(debug()); }
    public void naming(Action<BozarNamingExtension> action) { action.execute(naming()); }
    public void strings(Action<BozarOptionExtension> action) { action.execute(strings()); }
    public void watermark(Action<BozarWatermarkExtension> action) { action.execute(watermark()); }

    public BozarMinecraftExtension getMinecraft() { return minecraft(); }
    public BozarOptionExtension getRenamer() { return renamer(); }
    public BozarOptionExtension getControlFlow() { return controlFlow(); }
    public BozarOptionExtension getConstants() { return constants(); }
    public BozarDebugExtension getDebug() { return debug(); }
    public BozarNamingExtension getNaming() { return naming(); }
    public BozarOptionExtension getStrings() { return strings(); }
    public BozarWatermarkExtension getWatermark() { return watermark(); }

    // ------------------------------------------------------------------ memoized sections

    private BozarMinecraftExtension minecraft() {
        if (minecraft == null) {
            minecraft = new BozarMinecraftExtension(this.objects);
        }
        return minecraft;
    }

    private BozarOptionExtension renamer() {
        if (renamerSection == null) {
            renamerSection = new BozarOptionExtension(this.objects);
        }
        return renamerSection;
    }

    private BozarOptionExtension controlFlow() {
        if (controlFlowSection == null) {
            controlFlowSection = new BozarOptionExtension(this.objects);
        }
        return controlFlowSection;
    }

    private BozarOptionExtension constants() {
        if (constantsSection == null) {
            constantsSection = new BozarOptionExtension(this.objects);
        }
        return constantsSection;
    }

    private BozarDebugExtension debug() {
        if (debugSection == null) {
            debugSection = new BozarDebugExtension(this.objects);
        }
        return debugSection;
    }

    private BozarNamingExtension naming() {
        if (namingSection == null) {
            namingSection = new BozarNamingExtension(this.objects);
        }
        return namingSection;
    }

    private BozarOptionExtension strings() {
        if (stringsSection == null) {
            stringsSection = new BozarOptionExtension(this.objects);
        }
        return stringsSection;
    }

    private BozarWatermarkExtension watermark() {
        if (watermarkSection == null) {
            watermarkSection = new BozarWatermarkExtension(this.objects);
        }
        return watermarkSection;
    }
}
