package com.fortytwotalents.optimizer.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Gradle task that runs a Spring Boot application in training mode to detect
 * which auto-configurations are loaded.
 */
@DisableCachingByDefault(because = "Runs an external Spring Boot process whose output depends on the environment")
public abstract class TrainTask extends DefaultTask {

    /**
     * The main class of the Spring Boot application.
     */
    @Input
    @Optional
    public abstract Property<String> getMainClass();

    /**
     * The Spring Boot executable JAR to run.
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    @Optional
    public abstract RegularFileProperty getJar();

    /**
     * The classpath for running the application.
     */
    @Classpath
    @Optional
    public abstract ListProperty<File> getRuntimeClasspath();

    /**
     * The directories containing compiled class files to scan for the main class when
     * neither {@code jar} nor {@code mainClass} is configured.
     */
    @InputFiles
    @Classpath
    @Optional
    public abstract ConfigurableFileCollection getClassesDirectories();

    /**
     * Additional JVM arguments for the training run.
     */
    @Input
    @Optional
    public abstract ListProperty<String> getJvmArguments();

    /**
     * The training run timeout in seconds.
     */
    @Input
    public abstract Property<Integer> getTimeoutSeconds();

    /**
     * The output directory for the generated properties file.
     */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * The name of the generated properties file.
     */
    @Input
    public abstract Property<String> getOutputFile();

    @TaskAction
    public void train() throws IOException, InterruptedException {
        String outputFileName = getOutputFile().get();
        Path workDir = getOutputDirectory().get().getAsFile().toPath();
        Files.createDirectories(workDir);
        Path outputFilePath = workDir.resolve(outputFileName);

        // Delete any existing training file
        Files.deleteIfExists(outputFilePath);

        List<String> command = buildCommand(workDir, outputFileName);
        getLogger().info("Spring Boot Autoconfiguration Optimizer: Starting training run...");
        getLogger().debug("Training run command: {}", String.join(" ", command));

        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .inheritIO()
                .start();

        int timeoutSeconds = getTimeoutSeconds().get();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new GradleException("Training run timed out after " + timeoutSeconds + " seconds. "
                    + "Consider increasing the timeout or setting exitAfterTraining=true.");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new GradleException("Training run failed with exit code: " + exitCode);
        }

        if (!Files.exists(outputFilePath)) {
            throw new GradleException("Training run completed but no output file was generated at: "
                    + outputFilePath + ". Ensure autoconfiguration-optimizer-core is on the classpath.");
        }

        getLogger().lifecycle("Spring Boot Autoconfiguration Optimizer: Training run complete. "
                + "Generated: {}", outputFilePath);

        // Inject core classes into the main output so the optimizer works at runtime
        // without requiring the core as a project dependency
        injectCoreFiles();
    }

    private void injectCoreFiles() {
        Path coreJar = CoreInjector.findCoreJar();
        if (coreJar == null) {
            getLogger().debug(
                    "Spring Boot Autoconfiguration Optimizer: Core JAR not found as a file. Skipping core injection.");
            return;
        }
        // Inject into each classes directory configured for this task
        Iterable<File> dirs = resolveClassesDirectoriesForScan();
        boolean injected = false;
        for (File dir : dirs) {
            if (dir.isDirectory()) {
                try {
                    CoreInjector.injectCoreJarContents(coreJar, dir.toPath());
                    getLogger().lifecycle(
                            "Spring Boot Autoconfiguration Optimizer: Core classes injected into: {}", dir);
                    injected = true;
                }
                catch (java.io.IOException ex) {
                    throw new GradleException("Failed to inject optimizer core classes into " + dir, ex);
                }
            }
        }
        if (!injected) {
            getLogger().debug(
                    "Spring Boot Autoconfiguration Optimizer: No classes directories found for core injection.");
        }
    }

    private List<String> buildCommand(Path workDir, String outputFileName) {
        List<String> command = new ArrayList<>();

        String javaHome = System.getProperty("java.home");
        String javaExec = Paths.get(javaHome, "bin", "java").toString();
        command.add(javaExec);

        if (getJvmArguments().isPresent()) {
            command.addAll(getJvmArguments().get());
        }

        command.add("-Dautoconfiguration.optimizer.training-run=true");
        command.add("-Dautoconfiguration.optimizer.exit-after-training=true");
        command.add("-Dautoconfiguration.optimizer.output-directory=" + workDir.toAbsolutePath());
        command.add("-Dautoconfiguration.optimizer.output-file=" + outputFileName);

        if (getJar().isPresent() && getJar().get().getAsFile().exists()) {
            command.add("-jar");
            command.add(getJar().get().getAsFile().getAbsolutePath());
        } else if (getMainClass().isPresent()) {
            String classpath = buildClasspath();
            if (!classpath.isEmpty()) {
                command.add("-cp");
                command.add(classpath);
            }
            command.add(getMainClass().get());
        } else {
            // Try to auto-detect main class by scanning compiled class files
            Iterable<File> dirsToScan = resolveClassesDirectoriesForScan();
            java.util.Optional<String> detectedMainClass = MainClassFinder.findMainClass(dirsToScan);
            if (detectedMainClass.isPresent()) {
                String mainClassValue = detectedMainClass.get();
                getLogger().lifecycle(
                        "Spring Boot Autoconfiguration Optimizer: Auto-detected main class: {}",
                        mainClassValue);
                String classpath = buildClasspath();
                if (!classpath.isEmpty()) {
                    command.add("-cp");
                    command.add(classpath);
                }
                command.add(mainClassValue);
            } else {
                throw new GradleException(
                        "Could not determine main class. Please configure 'mainClass', 'jar', or "
                                + "'classesDirectories' in the autoconfigurationOptimizer extension, or ensure "
                                + "the project is compiled and contains a class annotated with @SpringBootApplication.");
            }
        }

        return command;
    }

    /**
     * Builds the classpath for the training subprocess. Always includes the optimizer
     * core JAR so training works without the user declaring it as a project dependency.
     */
    private String buildClasspath() {
        List<String> entries = new ArrayList<>();

        if (getRuntimeClasspath().isPresent() && !getRuntimeClasspath().get().isEmpty()) {
            getRuntimeClasspath().get().stream()
                    .map(File::getAbsolutePath)
                    .forEach(entries::add);
        }

        // Always include the optimizer core so the TrainingRunApplicationListener is
        // available without requiring the user to add it as a project dependency
        Path coreJar = CoreInjector.findCoreJar();
        if (coreJar != null && !entries.contains(coreJar.toString())) {
            entries.add(coreJar.toString());
        }

        return entries.stream().collect(Collectors.joining(File.pathSeparator));
    }

    private Iterable<File> resolveClassesDirectoriesForScan() {
        if (!getClassesDirectories().isEmpty()) {
            return getClassesDirectories().getFiles();
        }
        if (getRuntimeClasspath().isPresent() && !getRuntimeClasspath().get().isEmpty()) {
            return getRuntimeClasspath().get().stream().filter(File::isDirectory).collect(Collectors.toList());
        }
        return List.of();
    }
}
