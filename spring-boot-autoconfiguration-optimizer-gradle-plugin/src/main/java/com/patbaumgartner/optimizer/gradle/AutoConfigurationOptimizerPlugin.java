package com.patbaumgartner.optimizer.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;

/**
 * Gradle plugin for Spring Boot Autoconfiguration Optimizer.
 *
 * <p>
 * This plugin adds the following tasks:
 * <ul>
 * <li>{@code trainAutoconfiguration} - Runs the application in training mode to detect
 * which auto-configurations are loaded</li>
 * <li>{@code copyAutoconfigurationOptimizerFile} - Copies the generated training file
 * into the build output before the {@code jar} or {@code bootJar} task runs</li>
 * </ul>
 *
 * <p>
 * When the {@code java} plugin is applied, {@code autoconfiguration-optimizer-core} is
 * added to the {@code runtimeOnly} configuration at the version this plugin was built
 * with, so the packaged application contains the import filter that reads the training
 * file. Set {@code coreVersion} on the extension to override it.
 *
 * <p>
 * Usage in {@code build.gradle}:
 * 
 * <pre>{@code plugins { id 'com.patbaumgartner.autoconfiguration-optimizer' version '1.0.0' }
 *
 * autoconfigurationOptimizer {
 *     mainClass = 'com.example.MyApplication'
 *     timeout = 120
 * }
 * }</pre>
 */
public class AutoConfigurationOptimizerPlugin implements Plugin<Project> {

    /**
     * Creates a new {@code AutoConfigurationOptimizerPlugin}.
     */
    public AutoConfigurationOptimizerPlugin() {
    }

    /** The name of the configuration extension registered by this plugin. */
    public static final String EXTENSION_NAME = "autoconfigurationOptimizer";

    /** The name of the training task registered by this plugin. */
    public static final String TRAIN_TASK_NAME = "trainAutoconfiguration";

    /** The name of the task that copies the generated training file into the build output. */
    public static final String COPY_TASK_NAME = "copyAutoconfigurationOptimizerFile";

    /** The task group under which all plugin tasks are listed. */
    public static final String TASK_GROUP = "autoconfiguration optimizer";

    private static final String OPTIMIZER_CORE_COORDINATES = "com.patbaumgartner:autoconfiguration-optimizer-core:";

    @Override
    public void apply(Project project) {
        AutoConfigurationOptimizerExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, AutoConfigurationOptimizerExtension.class);

        // Set defaults
        extension.getTimeout().convention(120);
        extension.getOutputFile().convention("autoconfiguration-optimizer.properties");
        extension.getSkip().convention(false);
        extension.getTargetDirectory().convention(
                project.getLayout().getBuildDirectory().dir("classes/java/main/META-INF"));
        extension.getCoreVersion().convention(OptimizerCoreVersion.get());

        TaskProvider<TrainTask> trainTask = project.getTasks().register(TRAIN_TASK_NAME, TrainTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Runs a training run to detect loaded Spring Boot auto-configurations "
                    + "and generates an optimizer properties file.");

            task.getMainClass().set(extension.getMainClass());
            task.getJvmArguments().set(extension.getJvmArguments());
            task.getTimeoutSeconds().set(extension.getTimeout());
            task.getOutputFile().set(extension.getOutputFile());
            task.getOutputDirectory().set(
                    project.getLayout().getBuildDirectory().dir("autoconfiguration-optimizer"));

            // Set jar if configured
            task.getJar().fileProvider(project.provider(() -> {
                if (extension.getJar().isPresent()) {
                    Object jar = extension.getJar().get();
                    if (jar instanceof File file) {
                        return file;
                    }
                    return project.file(jar);
                }
                return null;
            }));
        });

        // A built-in Copy task rather than a doLast block: an ad-hoc task that reaches
        // back to the Project at execution time cannot be serialized into Gradle's
        // configuration cache, which is enabled by default from Gradle 9.
        TaskProvider<Copy> copyTask = project.getTasks().register(COPY_TASK_NAME, Copy.class,
                task -> {
                    task.setGroup(TASK_GROUP);
                    task.setDescription(
                            "Copies the generated autoconfiguration optimizer properties file to the build output directory.");
                    task.from(trainTask.flatMap(
                            train -> train.getOutputDirectory().file(train.getOutputFile())));
                    task.into(extension.getTargetDirectory());
                });

        // If the Java plugin is applied, automatically configure classesDirectories
        // so the main class can be auto-detected without any additional configuration,
        // and wire the train/copy tasks to run before jar
        project.getPlugins().withId("java", javaPlugin -> {
            addOptimizerCoreDependency(project, extension);

            trainTask.configure(task -> {
                task.dependsOn("classes");
                JavaPluginExtension javaExtension = project.getExtensions().findByType(JavaPluginExtension.class);
                if (javaExtension != null) {
                    SourceSetContainer sourceSets = javaExtension.getSourceSets();
                    task.getClassesDirectories()
                            .from(sourceSets.named("main").map(ss -> ss.getOutput().getClassesDirs()));
                    task.getRuntimeClasspath()
                            .set(project.provider(
                                    () -> sourceSets.getByName("main").getRuntimeClasspath().getFiles().stream()
                                            .collect(java.util.stream.Collectors.toList())));
                }
            });

            project.getTasks().named("jar").configure(jar -> jar.dependsOn(copyTask));

            // Spring Boot fat archives are produced by their own tasks that do NOT
            // depend on the regular 'jar' task, so they must be wired independently.
            // 'resolveMainClassName' scans the same classes directory the copy task
            // writes into, so Gradle rejects the build unless that ordering is declared.
            project.getPlugins().withId("org.springframework.boot", plugin -> {
                project.getTasks().named("bootJar").configure(task -> task.dependsOn(copyTask));
                project.getTasks().named("resolveMainClassName").configure(task -> task.dependsOn(copyTask));
                project.getPlugins().withId("war", warPlugin -> project.getTasks().named("bootWar")
                        .configure(task -> task.dependsOn(copyTask)));
            });
        });
    }

    /**
     * Adds the optimizer core to the project's runtime classpath.
     *
     * <p>
     * The application needs the import filter at runtime for the generated training file
     * to have any effect. Adding it here keeps the version aligned with the plugin
     * automatically; set {@code coreVersion} on the extension to override it.
     */
    private void addOptimizerCoreDependency(Project project, AutoConfigurationOptimizerExtension extension) {
        project.getDependencies().addProvider("runtimeOnly", extension.getCoreVersion()
                .map(version -> OPTIMIZER_CORE_COORDINATES + version));
    }
}
