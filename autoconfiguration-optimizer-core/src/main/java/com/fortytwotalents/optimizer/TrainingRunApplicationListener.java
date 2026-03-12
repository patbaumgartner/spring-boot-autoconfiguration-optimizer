package com.fortytwotalents.optimizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application listener that records which auto-configurations were loaded during a
 * training run.
 *
 * <p>
 * This listener is activated when {@code autoconfiguration.optimizer.training-run=true}
 * is set. It uses Spring Boot's {@link ConditionEvaluationReport} to determine which
 * auto-configurations were active and writes them to a properties file.
 *
 * <p>
 * The generated file should be placed in {@code src/main/resources/META-INF/} to be
 * picked up by subsequent builds and used by the
 * {@link OptimizedAutoConfigurationImportFilter}.
 *
 * @see OptimizedAutoConfigurationImportFilter
 * @see AutoConfigurationOptimizerProperties
 */
public class TrainingRunApplicationListener implements ApplicationListener<ApplicationStartedEvent> {

	private static final Logger log = LoggerFactory.getLogger(TrainingRunApplicationListener.class);

	static final String TRAINING_TIMESTAMP_KEY = "autoconfiguration.optimizer.training-timestamp";

	private final AutoConfigurationOptimizerProperties properties;

	private final ConditionEvaluationReport conditionEvaluationReport;

	public TrainingRunApplicationListener(AutoConfigurationOptimizerProperties properties,
			ConditionEvaluationReport conditionEvaluationReport) {
		this.properties = properties;
		this.conditionEvaluationReport = conditionEvaluationReport;
	}

	@Override
	public void onApplicationEvent(ApplicationStartedEvent event) {
		log.info("Spring Boot Autoconfiguration Optimizer: Training run started");

		try {
			Set<String> availableAutoConfigs = loadAvailableAutoConfigurations();
			List<String> loadedAutoConfigs = detectLoadedAutoConfigurations(availableAutoConfigs);
			writeTrainingFile(loadedAutoConfigs, availableAutoConfigs.size());

			log.atInfo()
				.addArgument(() -> loadedAutoConfigs.size())
				.addArgument(() -> availableAutoConfigs.size())
				.addArgument(() -> availableAutoConfigs.size() - loadedAutoConfigs.size())
				.log("Spring Boot Autoconfiguration Optimizer: Training run complete. "
						+ "Detected {} loaded auto-configurations out of {} available ({} excluded).");
		}
		catch (Exception e) {
			log.error("Spring Boot Autoconfiguration Optimizer: Training run failed" + " (loading/detecting auto-configurations or writing training file)", e);
		}
		finally {
			if (properties.isExitAfterTraining()) {
				log.info("Spring Boot Autoconfiguration Optimizer: Exiting after training run.");
				int exitCode = SpringApplication.exit(event.getApplicationContext(), () -> 0);
				System.exit(exitCode);
			}
		}
	}

	/**
	 * Detects which auto-configurations were loaded by cross-referencing the
	 * {@link ConditionEvaluationReport} with all available auto-configurations on the
	 * classpath.
	 *
	 * <p>
	 * Auto-configurations fall into two categories in the report:
	 * <ol>
	 * <li>Those with class-level conditions (e.g. {@code @ConditionalOnClass}) appear as
	 * top-level keys and are included only when {@code isFullMatch()} is
	 * {@code true}.</li>
	 * <li>Those without class-level conditions (always loaded) appear only as
	 * {@code ClassName#methodName} keys for their {@code @Bean} methods. These must also
	 * be captured, otherwise they are incorrectly excluded by the optimizer.</li>
	 * </ol>
	 */
	List<String> detectLoadedAutoConfigurations() {
		return detectLoadedAutoConfigurations(loadAvailableAutoConfigurations());
	}

	private List<String> detectLoadedAutoConfigurations(Set<String> availableAutoConfigs) {
		Map<String, ConditionEvaluationReport.ConditionAndOutcomes> conditionOutcomes = conditionEvaluationReport
			.getConditionAndOutcomesBySource();

		// Collect class names that appear only as method-level entries
		// (e.g. "com.example.FooAutoConfiguration#fooBean" ->
		// "com.example.FooAutoConfiguration").
		// Note: '#' is the method separator used by ConditionEvaluationReport;
		// inner-class names use '$' and are unaffected by this filter.
		Set<String> classesFromMethodEntries = conditionOutcomes.keySet()
			.stream()
			.filter(key -> key.contains("#"))
			.map(key -> key.substring(0, key.indexOf('#')))
			.collect(Collectors.toSet());

		String optimizerAutoConfigClassName = AutoConfigurationOptimizerAutoConfiguration.class.getName();

		return availableAutoConfigs.stream().filter(config -> {
			// Always exclude the optimizer's own auto-configuration: it is only useful
			// during a training run and must not be recorded so that it is also excluded
			// on subsequent production runs.
			if (optimizerAutoConfigClassName.equals(config)) {
				return false;
			}
			ConditionEvaluationReport.ConditionAndOutcomes outcomes = conditionOutcomes.get(config);
			if (outcomes != null) {
				// Class-level conditions exist: include only when all conditions passed
				return outcomes.isFullMatch();
			}
			// No class-level conditions: include when the class appears in any
			// bean-method
			// entry, which confirms it was loaded unconditionally
			return classesFromMethodEntries.contains(config);
		}).sorted().collect(Collectors.toList());
	}

	/**
	 * Loads all available auto-configuration class names using Spring Boot's
	 * {@link ImportCandidates} mechanism.
	 */
	Set<String> loadAvailableAutoConfigurations() {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		Set<String> autoConfigs = new HashSet<>(
				ImportCandidates.load(AutoConfiguration.class, classLoader).getCandidates());
		log.atDebug()
			.addArgument(() -> autoConfigs.size())
			.log("Spring Boot Autoconfiguration Optimizer: Found {} available auto-configurations");
		return autoConfigs;
	}

	/**
	 * Writes the list of loaded auto-configurations to the output file.
	 */
	void writeTrainingFile(List<String> loadedAutoConfigs, int totalAvailableCount) throws IOException {
		Path outputDir = Path.of(properties.getOutputDirectory());
		Files.createDirectories(outputDir);

		Path outputFile = outputDir.resolve(properties.getOutputFile());

		List<String> lines = new ArrayList<>();
		lines.add("# Generated by Spring Boot Autoconfiguration Optimizer");
		lines.add("# Training run completed on: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
		lines.add("# Copy this file to src/main/resources/META-INF/ to enable optimization");
		lines.add("#");
		lines.add("# Total available auto-configurations: " + totalAvailableCount);
		lines.add("# Total auto-configurations loaded: " + loadedAutoConfigs.size());
		lines.add("# Auto-configurations excluded: " + (totalAvailableCount - loadedAutoConfigs.size()));
		lines.add("");
		lines.add(TRAINING_TIMESTAMP_KEY + "=" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
		lines.add("");

		if (loadedAutoConfigs.isEmpty()) {
			lines.add(OptimizedAutoConfigurationImportFilter.LOADED_CONFIGURATIONS_KEY + "=");
		}
		else {
			StringBuilder sb = new StringBuilder(
					OptimizedAutoConfigurationImportFilter.LOADED_CONFIGURATIONS_KEY + "=\\\n");
			for (int i = 0; i < loadedAutoConfigs.size(); i++) {
				sb.append("  ").append(loadedAutoConfigs.get(i));
				if (i < loadedAutoConfigs.size() - 1) {
					sb.append(",\\\n");
				}
			}
			lines.add(sb.toString());
		}

		Files.write(outputFile, lines, StandardCharsets.UTF_8);
		log.atInfo()
			.addArgument(() -> outputFile.toAbsolutePath())
			.log("Spring Boot Autoconfiguration Optimizer: Training file written to: {}");
	}

}
