package io.github.vimasig.bozar.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Applies the {@code bozar { }} extension and registers the {@code obfuscate} task.
 *
 * <p>Requires the {@code java} plugin for its default input (the {@code jar} task's output).
 * Projects that want a non-Java or hand-supplied input JAR set {@code inputJar} explicitly;
 * the task then has no dependency on {@code jar}.
 */
public class BozarPlugin implements Plugin<Project> {

    public static final String EXTENSION_NAME = "bozar";
    public static final String TASK_NAME = "obfuscate";

    @Override
    public void apply(Project project) {
        if (project.getPluginManager().findPlugin("java") == null) {
            project.getPluginManager().apply("java");
        }

        final BozarExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, BozarExtension.class, project);
        extension.wire(project);
    }
}