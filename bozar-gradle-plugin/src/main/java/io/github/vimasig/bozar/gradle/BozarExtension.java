package io.github.vimasig.bozar.gradle;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
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
    private final RegularFileProperty inputJar;
    private final RegularFileProperty outputJar;
    private final RegularFileProperty mappingOutput;
    private final ListProperty<String> keepPatterns;
    private final ConfigurableFileCollection classpath;

    private BozarMinecraftExtension minecraft;
    private BozarToggleExtension renamerSection;
    private BozarToggleExtension controlFlowSection;
    private BozarToggleExtension constantsSection;
    private BozarDebugExtension debugSection;
    private BozarNamingExtension namingSection;

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

        this.inputJar = this.objects.fileProperty();
        this.outputJar = this.objects.fileProperty();
        this.mappingOutput = this.objects.fileProperty();
        this.keepPatterns = this.objects.listProperty(String.class).convention(new ArrayList<>());
        this.classpath = project.files();

        // Defaults are providers: the jar task is only configured when an output is needed.
        final Provider<Jar> jarTask = jarTaskProvider(project);
        this.inputJar.convention(jarTask.flatMap(Jar::getArchiveFile));
        this.outputJar.convention(project.getLayout().getBuildDirectory().file(
                project.provider(() -> "libs/" + project.getName() + "-obfuscated.jar")));
        this.mappingOutput.convention(project.getLayout().getBuildDirectory().file(
                "bozar/mapping.txt"));
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
        final FileCollection runtimeClasspath = runtimeClasspathOf(project);

        // Configuration-cache requirement: anything the task retains must not reach `Project`
        // through a captured `this`. These locals hold only Gradle provider types.
        final Property<Boolean> enabledFlag = this.enabled;
        final Property<Boolean> mappingFlag = this.mappingEnabled;
        final RegularFileProperty mappingFile = this.mappingOutput;
        final ProjectLayout layout = project.getLayout();
        final ProviderFactory providers = project.getProviders();

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
                    task.getControlFlowEnabled().set(controlFlow().getEnabled());
                    task.getConstantsEnabled().set(constants().getEnabled());
                    task.getRemoveSourceFile().set(debug().getRemoveSourceFile());
                    task.getRemoveLineNumbers().set(debug().getRemoveLineNumbers());
                    task.getRemoveLocalVariables().set(debug().getRemoveLocalVariables());
                    // The section wins over a top-level seed: it is the more specific declaration.
                    task.getSeed().set(naming().getSeed().orElse(seed));
                    task.getVerify().set(verify);
                    task.getMinecraftEnabled().set(minecraft().getEnabled());
                    task.getEntrypointMode().set(entrypointMode);
                    task.getKeepServiceProviders().set(keepServiceProviders);
                    task.getKeepPatterns().set(keepPatterns);
                    task.getClasspath().from(classpath);
                    task.getClasspath().from(runtimeClasspath);

                    // Skipping rather than not registering keeps `build`'s dependency graph
                    // stable, which is what makes configuration-cache reuse work across both
                    // the enabled and disabled states.
                    task.onlyIf(t -> enabledFlag.getOrElse(true));
                    task.dependsOn(jarTask);
                });

        // Must be outside the registration action: the action runs only when the task is
        // realized, and nothing would realize it, so the edge would never be added.
        project.getTasks().named("build").configure(t -> t.dependsOn(obfuscate));
    }

    /** Runtime classpath of the main source set; those classes must never be renamed. */
    private FileCollection runtimeClasspathOf(Project project) {
        try {
            final SourceSetContainer sourceSets = (SourceSetContainer) project.getExtensions()
                    .findByName("sourceSets");
            if (sourceSets != null && sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME) != null) {
                // Not a ConfigurableFileCollection: SourceSet returns a CompositeFileCollection,
                // so casting here threw and the libraries were silently dropped. The engine's
                // classloader is built from the surviving files, which is why Minecraft API types
                // were unresolvable during verification.
                return sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).getRuntimeClasspath();
            }
        } catch (Exception ignored) {
            // No java plugin applied: nothing to add.
        }
        return project.files();
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
    public ConfigurableFileCollection getClasspath() { return classpath; }
    public Property<Boolean> getKeepServiceProviders() { return keepServiceProviders; }
    public Property<ObfuscateTask.EntrypointMode> getEntrypointMode() { return entrypointMode; }

    public void keep(String className) {
        keepPatterns.add(className.trim());
    }

    public void keepPackage(String packageName) {
        final String name = packageName.trim();
        keepPatterns.add(name.endsWith(".**") ? name : name + ".**");
    }

    public void minecraft(Action<BozarMinecraftExtension> action) { action.execute(minecraft()); }
    public void renamer(Action<BozarToggleExtension> action) { action.execute(renamer()); }
    public void controlFlow(Action<BozarToggleExtension> action) { action.execute(controlFlow()); }
    public void constants(Action<BozarToggleExtension> action) { action.execute(constants()); }
    public void debug(Action<BozarDebugExtension> action) { action.execute(debug()); }
    public void naming(Action<BozarNamingExtension> action) { action.execute(naming()); }

    public BozarMinecraftExtension getMinecraft() { return minecraft(); }
    public BozarToggleExtension getRenamer() { return renamer(); }
    public BozarToggleExtension getControlFlow() { return controlFlow(); }
    public BozarToggleExtension getConstants() { return constants(); }
    public BozarDebugExtension getDebug() { return debug(); }
    public BozarNamingExtension getNaming() { return naming(); }

    // ------------------------------------------------------------------ memoized sections

    private BozarMinecraftExtension minecraft() {
        if (minecraft == null) {
            minecraft = new BozarMinecraftExtension(this.objects);
        }
        return minecraft;
    }

    private BozarToggleExtension renamer() {
        if (renamerSection == null) {
            renamerSection = new BozarToggleExtension(this.objects);
        }
        return renamerSection;
    }

    private BozarToggleExtension controlFlow() {
        if (controlFlowSection == null) {
            controlFlowSection = new BozarToggleExtension(this.objects);
        }
        return controlFlowSection;
    }

    private BozarToggleExtension constants() {
        if (constantsSection == null) {
            constantsSection = new BozarToggleExtension(this.objects);
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
}
