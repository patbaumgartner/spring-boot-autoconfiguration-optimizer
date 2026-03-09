package com.fortytwotalents.optimizer.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

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
    @Optional
    public abstract RegularFileProperty getJar();

    /**
     * The classpath for running the application.
     */
    @Classpath
    @Optional
    public abstract ListProperty<File> getRuntimeClasspath();

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
            if (getRuntimeClasspath().isPresent() && !getRuntimeClasspath().get().isEmpty()) {
                String classpath = getRuntimeClasspath().get().stream()
                        .map(File::getAbsolutePath)
                        .collect(Collectors.joining(File.pathSeparator));
                command.add("-cp");
                command.add(classpath);
            }
            command.add(getMainClass().get());
        } else {
            throw new GradleException("Either 'jar' or 'mainClass' must be configured for the trainAutoconfiguration task.");
        }

        return command;
    }
}
