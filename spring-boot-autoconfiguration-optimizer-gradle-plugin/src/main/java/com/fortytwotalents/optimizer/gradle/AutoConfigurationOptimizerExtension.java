package com.fortytwotalents.optimizer.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Configuration extension for the Spring Boot Autoconfiguration Optimizer Gradle plugin.
 */
public abstract class AutoConfigurationOptimizerExtension {

    /**
     * The main class of the Spring Boot application.
     * Required if the application is not run via a bootJar.
     */
    public abstract Property<String> getMainClass();

    /**
     * The location of the Spring Boot executable JAR.
     * If set, the training run will use this JAR.
     */
    public abstract Property<Object> getJar();

    /**
     * Additional JVM arguments for the training run.
     */
    public abstract ListProperty<String> getJvmArguments();

    /**
     * The training run timeout in seconds.
     * Default: 120 seconds.
     */
    public abstract Property<Integer> getTimeout();

    /**
     * The target directory where the generated properties file will be copied.
     * Default: {@code src/main/resources/META-INF/}.
     */
    public abstract DirectoryProperty getTargetDirectory();

    /**
     * The name of the generated properties file.
     * Default: {@code autoconfiguration-optimizer.properties}.
     */
    public abstract Property<String> getOutputFile();

    /**
     * Whether to skip the training run.
     * Default: {@code false}.
     */
    public abstract Property<Boolean> getSkip();
}
