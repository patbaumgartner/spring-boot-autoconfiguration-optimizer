package com.fortytwotalents.optimizer.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

import java.io.File;

/**
 * Gradle plugin for Spring Boot Autoconfiguration Optimizer.
 *
 * <p>This plugin adds the following tasks:
 * <ul>
 *   <li>{@code trainAutoconfiguration} - Runs the application in training mode to detect
 *       which auto-configurations are loaded</li>
 *   <li>{@code injectOptimizerCore} - Injects the optimizer core classes into the build
 *       output before the {@code jar} task runs</li>
 * </ul>
 *
 * <p>The {@code autoconfiguration-optimizer-core} JAR is automatically added to the
 * training subprocess classpath, so users do <em>not</em> need to declare it as a project
 * dependency. After training the core classes are also injected into the main output
 * directory so they are included in the packaged JAR.
 *
 * <p>Usage in {@code build.gradle}:
 * <pre>{@code
 * plugins {
 *     id 'com.fortytwotalents.autoconfiguration-optimizer' version '1.0.0'
 * }
 *
 * autoconfigurationOptimizer {
 *     mainClass = 'com.example.MyApplication'
 *     timeout = 120
 * }
 * }</pre>
 */
public class AutoConfigurationOptimizerPlugin implements Plugin<Project> {

    public static final String EXTENSION_NAME = "autoconfigurationOptimizer";
    public static final String TRAIN_TASK_NAME = "trainAutoconfiguration";
    public static final String INJECT_TASK_NAME = "injectOptimizerCore";
    public static final String TASK_GROUP = "autoconfiguration optimizer";

    @Override
    public void apply(Project project) {
        AutoConfigurationOptimizerExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, AutoConfigurationOptimizerExtension.class);

        // Set defaults
        extension.getTimeout().convention(120);
        extension.getOutputFile().convention("autoconfiguration-optimizer.properties");
        extension.getSkip().convention(false);
        extension.getTargetDirectory().convention(
                project.getLayout().getProjectDirectory().dir("src/main/resources/META-INF")
        );

        TaskProvider<TrainTask> trainTask = project.getTasks().register(TRAIN_TASK_NAME, TrainTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Runs a training run to detect loaded Spring Boot auto-configurations "
                    + "and generates an optimizer properties file.");

            task.getMainClass().set(extension.getMainClass());
            task.getJvmArguments().set(extension.getJvmArguments());
            task.getTimeoutSeconds().set(extension.getTimeout());
            task.getOutputFile().set(extension.getOutputFile());
            task.getOutputDirectory().set(
                    project.getLayout().getBuildDirectory().dir("autoconfiguration-optimizer")
            );

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

        // If the Java plugin is applied, automatically configure classesDirectories
        // so the main class can be auto-detected without any additional configuration,
        // and wire the inject task to run before jar
        project.getPlugins().withId("java", javaPlugin -> {
            trainTask.configure(task -> {
                JavaPluginExtension javaExtension = project.getExtensions().findByType(JavaPluginExtension.class);
                if (javaExtension != null) {
                    SourceSetContainer sourceSets = javaExtension.getSourceSets();
                    task.getClassesDirectories()
                            .from(sourceSets.named("main").map(ss -> ss.getOutput().getClassesDirs()));
                }
            });

            // Register the inject task and wire it to run before 'jar'
            project.getTasks().register(INJECT_TASK_NAME, InjectTask.class, task -> {
                task.setGroup(TASK_GROUP);
                task.setDescription("Injects optimizer core classes into the build output before packaging.");

                JavaPluginExtension javaExtension = project.getExtensions().findByType(JavaPluginExtension.class);
                if (javaExtension != null) {
                    SourceSetContainer sourceSets = javaExtension.getSourceSets();
                    SourceSet mainSourceSet = sourceSets.getByName("main");

                    // Point the output directory at the first classes dir for the main source set
                    task.getOutputDirectory().set(
                            project.getLayout().getBuildDirectory().dir("classes/java/main")
                    );

                    // The training file whose presence triggers injection
                    task.getTrainingFile().fileProvider(project.provider(() -> {
                        File trainingFile = extension.getTargetDirectory().get().getAsFile().toPath()
                                .resolve(extension.getOutputFile().get()).toFile();
                        return trainingFile.exists() ? trainingFile : null;
                    }));
                }
            });

            // Make 'jar' depend on 'injectOptimizerCore' so the core classes are always
            // present when the project is packaged (after a training run)
            project.getTasks().named("jar").configure(jar -> jar.dependsOn(INJECT_TASK_NAME));
        });

        // Also add a copy task that copies the generated file to the target directory
        project.getTasks().register("copyAutoconfigurationOptimizerFile", task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Copies the generated autoconfiguration optimizer properties file to the resources directory.");
            task.dependsOn(trainTask);
            task.doLast(t -> {
                File sourceFile = project.getLayout().getBuildDirectory()
                        .dir("autoconfiguration-optimizer")
                        .get()
                        .file(extension.getOutputFile().get())
                        .getAsFile();

                if (!sourceFile.exists()) {
                    throw new org.gradle.api.GradleException(
                            "Training output file not found: " + sourceFile.getAbsolutePath());
                }

                File targetDir = extension.getTargetDirectory().get().getAsFile();
                targetDir.mkdirs();
                File targetFile = new File(targetDir, extension.getOutputFile().get());

                try {
                    java.nio.file.Files.copy(
                            sourceFile.toPath(),
                            targetFile.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    );
                    t.getLogger().lifecycle("Copied optimizer properties to: {}", targetFile.getAbsolutePath());
                } catch (java.io.IOException e) {
                    throw new org.gradle.api.GradleException("Failed to copy optimizer properties file", e);
                }
            });
        });
    }
}
