package com.fortytwotalents.optimizer.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Configuration extension for the Spring Boot Autoconfiguration Optimizer
 * Gradle plugin.
 */
public abstract class AutoConfigurationOptimizerExtension {

    /**
     * Creates a new {@code AutoConfigurationOptimizerExtension}.
     */
    public AutoConfigurationOptimizerExtension() {
    }

    /**
     * The main class of the Spring Boot application.
     * Required if the application is not run via a bootJar.
     *
     * @return the main class property
     */
    public abstract Property<String> getMainClass();

    /**
     * The location of the Spring Boot executable JAR.
     * If set, the training run will use this JAR.
     *
     * @return the JAR property
     */
    public abstract Property<Object> getJar();

    /**
     * Additional JVM arguments for the training run.
     *
     * @return the JVM arguments list property
     */
    public abstract ListProperty<String> getJvmArguments();

    /**
     * The training run timeout in seconds.
     * Default: 120 seconds.
     *
     * @return the timeout property
     */
    public abstract Property<Integer> getTimeout();

    /**
     * The target directory where the generated properties file will be copied.
     * Default: {@code build/classes/java/main/META-INF/}.
     *
     * @return the target directory property
     */
    public abstract DirectoryProperty getTargetDirectory();

    /**
     * The name of the generated properties file.
     * Default: {@code autoconfiguration-optimizer.properties}.
     *
     * @return the output file name property
     */
    public abstract Property<String> getOutputFile();

    /**
     * Whether to skip the training run.
     * Default: {@code false}.
     *
     * @return the skip property
     */
    public abstract Property<Boolean> getSkip();
}
