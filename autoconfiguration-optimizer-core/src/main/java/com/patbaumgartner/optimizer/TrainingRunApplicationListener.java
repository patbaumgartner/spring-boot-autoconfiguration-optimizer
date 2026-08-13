package com.patbaumgartner.optimizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Application listener that records which auto-configurations were <em>not</em> used
 * during a training run.
 *
 * <p>
 * This listener is activated when {@code autoconfiguration.optimizer.training-run=true}
 * is set. It cross-references Spring Boot's {@link ConditionEvaluationReport} with the
 * registered auto-configuration candidates and writes everything that did not apply to a
 * properties file.
 *
 * <p>
 * The generated file should be placed in {@code META-INF/} on the classpath to be picked
 * up by subsequent builds and used by the {@link OptimizedAutoConfigurationImportFilter}.
 *
 * @see OptimizedAutoConfigurationImportFilter
 * @see TrainingFile
 */
public class TrainingRunApplicationListener implements ApplicationListener<ApplicationStartedEvent> {

	private static final Logger log = LoggerFactory.getLogger(TrainingRunApplicationListener.class);

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

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

		Exception failure = null;
		try {
			Set<String> candidates = loadAutoConfigurationCandidates();
			Set<String> loaded = detectLoadedAutoConfigurations(candidates);
			List<String> excluded = candidates.stream()
				.filter((candidate) -> !loaded.contains(candidate))
				.sorted()
				.toList();

			writeTrainingFile(excluded, candidates);

			log.info(
					"Spring Boot Autoconfiguration Optimizer: Training run complete. {} of {} auto-configurations were "
							+ "used; the remaining {} will be skipped at startup.",
					loaded.size(), candidates.size(), excluded.size());
		}
		catch (Exception ex) {
			failure = ex;
			log.error("Spring Boot Autoconfiguration Optimizer: Training run failed", ex);
		}

		if (this.properties.isExitAfterTraining()) {
			// The build plugins run the training in a forked JVM and treat a non-zero
			// exit as a build failure, so the status must reflect what actually happened.
			int status = (failure == null) ? 0 : 1;
			log.info("Spring Boot Autoconfiguration Optimizer: Exiting after training run with status {}.", status);
			System.exit(SpringApplication.exit(event.getApplicationContext(), () -> status));
		}
		else if (failure != null) {
			throw new IllegalStateException(
					"Spring Boot Autoconfiguration Optimizer: Training run failed and produced no usable training file",
					failure);
		}
	}

	/**
	 * Determines which of the given candidates Spring Boot actually loaded.
	 *
	 * <p>
	 * An auto-configuration counts as loaded when its class-level conditions all matched,
	 * or when it appears in {@link ConditionEvaluationReport#getUnconditionalClasses()},
	 * which holds the candidates that survived Spring Boot's own import filters without
	 * having any class-level condition evaluated. Candidates rejected by those filters
	 * appear in neither collection and are therefore correctly treated as unused.
	 * @param candidates the registered auto-configuration candidates
	 * @return the subset that was loaded
	 */
	Set<String> detectLoadedAutoConfigurations(Set<String> candidates) {
		Map<String, ConditionEvaluationReport.ConditionAndOutcomes> outcomesBySource = this.conditionEvaluationReport
			.getConditionAndOutcomesBySource();
		Set<String> unconditional = this.conditionEvaluationReport.getUnconditionalClasses();

		Set<String> loaded = new LinkedHashSet<>();
		for (String candidate : candidates) {
			ConditionEvaluationReport.ConditionAndOutcomes outcomes = outcomesBySource.get(candidate);
			boolean applied = (outcomes != null) ? outcomes.isFullMatch() : unconditional.contains(candidate);
			if (applied) {
				loaded.add(candidate);
			}
		}

		// The optimizer's own auto-configuration is only useful during training, so it is
		// deliberately reported as unused and ends up in the exclusion list.
		loaded.remove(AutoConfigurationOptimizerAutoConfiguration.class.getName());
		return loaded;
	}

	/**
	 * Loads all registered auto-configuration candidate class names using Spring Boot's
	 * {@link ImportCandidates} mechanism.
	 * @return the candidate class names
	 */
	Set<String> loadAutoConfigurationCandidates() {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		Set<String> candidates = new TreeSet<>();
		ImportCandidates.load(AutoConfiguration.class, classLoader).forEach(candidates::add);
		log.debug("Spring Boot Autoconfiguration Optimizer: Found {} auto-configuration candidates", candidates.size());
		return candidates;
	}

	/**
	 * Writes the auto-configurations to skip to the configured output file.
	 * @param excludedAutoConfigs the auto-configurations that were not used
	 * @param candidates all registered candidates seen during training
	 * @throws IOException if the file cannot be written
	 */
	void writeTrainingFile(List<String> excludedAutoConfigs, Set<String> candidates) throws IOException {
		Path outputDir = Path.of(this.properties.getOutputDirectory());
		Files.createDirectories(outputDir);

		// Validate the output file name to prevent path traversal: it must be a simple
		// filename with no directory components.
		Path outputFileName = Path.of(this.properties.getOutputFile());
		if (outputFileName.isAbsolute() || outputFileName.getNameCount() != 1) {
			throw new IllegalArgumentException(
					"Output file name must be a simple filename without directory components: " + outputFileName);
		}
		Path outputFile = outputDir.resolve(outputFileName.getFileName());

		String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
		int loadedCount = candidates.size() - excludedAutoConfigs.size();

		List<String> lines = new ArrayList<>();
		lines.add("# Generated by Spring Boot Autoconfiguration Optimizer");
		lines.add("# Training run completed on: " + timestamp);
		lines.add("# This file is placed in META-INF/ on the classpath by the build plugin.");
		lines.add("#");
		lines.add("# Auto-configuration candidates at training time: " + candidates.size());
		lines.add("# Loaded during training: " + loadedCount);
		lines.add("# Excluded on subsequent starts: " + excludedAutoConfigs.size());
		lines.add("");
		lines.add(TrainingFile.FORMAT_VERSION_KEY + "=" + TrainingFile.FORMAT_VERSION);
		lines.add(TrainingFile.TRAINING_TIMESTAMP_KEY + "=" + timestamp);
		lines.add(TrainingFile.SPRING_BOOT_VERSION_KEY + "=" + springBootVersion());
		lines.add(TrainingFile.CANDIDATE_COUNT_KEY + "=" + candidates.size());
		lines.add(TrainingFile.CANDIDATE_DIGEST_KEY + "=" + TrainingFile.candidateDigest(candidates));
		lines.add("");
		lines.add(TrainingFile.EXCLUDED_CONFIGURATIONS_KEY + "="
				+ (excludedAutoConfigs.isEmpty() ? "" : "\\\n  " + String.join(",\\\n  ", excludedAutoConfigs)));

		writeAtomically(outputFile, lines);
		log.info("Spring Boot Autoconfiguration Optimizer: Training file written to: {}", outputFile.toAbsolutePath());
	}

	/**
	 * Writes via a temporary file in the same directory and renames it into place, so a
	 * training run that dies midway cannot leave a truncated file that a later build
	 * would happily package.
	 */
	private static void writeAtomically(Path outputFile, List<String> lines) throws IOException {
		Path directory = outputFile.toAbsolutePath().getParent();
		Path temporaryFile = Files.createTempFile(directory, outputFile.getFileName().toString(), ".tmp");
		try {
			Files.write(temporaryFile, lines, StandardCharsets.UTF_8);
			try {
				Files.move(temporaryFile, outputFile, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException ex) {
				Files.move(temporaryFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally {
			Files.deleteIfExists(temporaryFile);
		}
	}

	private static String springBootVersion() {
		String version = SpringBootVersion.getVersion();
		return (version != null) ? version : "unknown";
	}

}
