package com.fortytwotalents.optimizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application listener that records which auto-configurations were loaded during a training run.
 *
 * <p>This listener is activated when {@code autoconfiguration.optimizer.training-run=true} is set.
 * It uses Spring Boot's {@link ConditionEvaluationReport} to determine which auto-configurations
 * were active and writes them to a properties file.
 *
 * <p>The generated file should be placed in {@code src/main/resources/META-INF/} to be picked up
 * by subsequent builds and used by the {@link OptimizedAutoConfigurationEnvironmentPostProcessor}.
 *
 * @see OptimizedAutoConfigurationEnvironmentPostProcessor
 * @see AutoConfigurationOptimizerProperties
 */
public class TrainingRunApplicationListener implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(TrainingRunApplicationListener.class);

    static final String AUTO_CONFIGURATION_IMPORTS_LOCATION =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    static final String LOADED_CONFIGURATIONS_KEY = "autoconfiguration.optimizer.loaded-configurations";
    static final String TRAINING_TIMESTAMP_KEY = "autoconfiguration.optimizer.training-timestamp";

    private final AutoConfigurationOptimizerProperties properties;
    private final ConditionEvaluationReport conditionEvaluationReport;

    public TrainingRunApplicationListener(
            AutoConfigurationOptimizerProperties properties,
            ConditionEvaluationReport conditionEvaluationReport) {
        this.properties = properties;
        this.conditionEvaluationReport = conditionEvaluationReport;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("Spring Boot Autoconfiguration Optimizer: Training run started");

        try {
            List<String> loadedAutoConfigs = detectLoadedAutoConfigurations();
            writeTrainingFile(loadedAutoConfigs);

            log.info("Spring Boot Autoconfiguration Optimizer: Training run complete. "
                    + "Detected {} loaded auto-configurations.", loadedAutoConfigs.size());

            if (properties.isExitAfterTraining()) {
                log.info("Spring Boot Autoconfiguration Optimizer: Exiting after training run.");
                int exitCode = SpringApplication.exit(event.getApplicationContext(), () -> 0);
                System.exit(exitCode);
            }
        } catch (IOException e) {
            log.error("Spring Boot Autoconfiguration Optimizer: Failed to write training file", e);
        }
    }

    /**
     * Detects which auto-configurations were loaded by cross-referencing the
     * {@link ConditionEvaluationReport} with all available auto-configurations on the classpath.
     */
    List<String> detectLoadedAutoConfigurations() throws IOException {
        Set<String> availableAutoConfigs = loadAvailableAutoConfigurations();

        Map<String, ConditionEvaluationReport.ConditionAndOutcomes> conditionOutcomes =
                conditionEvaluationReport.getConditionAndOutcomesBySource();

        return conditionOutcomes.entrySet().stream()
                .filter(entry -> availableAutoConfigs.contains(entry.getKey()))
                .filter(entry -> entry.getValue().isFullMatch())
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Loads all available auto-configuration class names from
     * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
     * files on the classpath.
     */
    Set<String> loadAvailableAutoConfigurations() throws IOException {
        Set<String> autoConfigs = new HashSet<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> resources = classLoader.getResources(AUTO_CONFIGURATION_IMPORTS_LOCATION);

        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(autoConfigs::add);
            }
        }

        log.debug("Spring Boot Autoconfiguration Optimizer: Found {} available auto-configurations",
                autoConfigs.size());
        return autoConfigs;
    }

    /**
     * Writes the list of loaded auto-configurations to the output file.
     */
    void writeTrainingFile(List<String> loadedAutoConfigs) throws IOException {
        Path outputDir = Paths.get(properties.getOutputDirectory());
        Files.createDirectories(outputDir);

        Path outputFile = outputDir.resolve(properties.getOutputFile());

        List<String> lines = new ArrayList<>();
        lines.add("# Generated by Spring Boot Autoconfiguration Optimizer");
        lines.add("# Training run completed on: "
                + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        lines.add("# Copy this file to src/main/resources/META-INF/ to enable optimization");
        lines.add("#");
        lines.add("# Total auto-configurations loaded: " + loadedAutoConfigs.size());
        lines.add("");
        lines.add(TRAINING_TIMESTAMP_KEY + "="
                + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        lines.add("");

        if (loadedAutoConfigs.isEmpty()) {
            lines.add(LOADED_CONFIGURATIONS_KEY + "=");
        } else {
            StringBuilder sb = new StringBuilder(LOADED_CONFIGURATIONS_KEY + "=\\\n");
            for (int i = 0; i < loadedAutoConfigs.size(); i++) {
                sb.append("  ").append(loadedAutoConfigs.get(i));
                if (i < loadedAutoConfigs.size() - 1) {
                    sb.append(",\\\n");
                }
            }
            lines.add(sb.toString());
        }

        Files.write(outputFile, lines, StandardCharsets.UTF_8);
        log.info("Spring Boot Autoconfiguration Optimizer: Training file written to: {}", outputFile.toAbsolutePath());
    }
}
