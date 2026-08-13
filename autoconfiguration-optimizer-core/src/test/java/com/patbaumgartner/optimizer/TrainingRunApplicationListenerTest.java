package com.patbaumgartner.optimizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrainingRunApplicationListenerTest {

	private static final String LOADED = "com.example.LoadedAutoConfiguration";

	private static final String UNCONDITIONAL = "com.example.UnconditionalAutoConfiguration";

	private static final String NOT_MATCHED = "com.example.NotMatchedAutoConfiguration";

	private static final String NEVER_EVALUATED = "com.example.FilteredOutAutoConfiguration";

	@TempDir
	Path tempDir;

	@Test
	void recordsAutoConfigurationsWhoseClassLevelConditionsMatched() {
		ConditionEvaluationReport report = report(Map.of(LOADED, true, NOT_MATCHED, false), Set.of());

		Set<String> loaded = listener(report).detectLoadedAutoConfigurations(Set.of(LOADED, NOT_MATCHED));

		assertThat(loaded).containsExactly(LOADED);
	}

	/**
	 * Auto-configurations without class-level conditions never reach
	 * {@code getConditionAndOutcomesBySource}; Spring Boot reports them as unconditional.
	 * Treating an absent entry as "not loaded" would exclude configurations the
	 * application always uses.
	 */
	@Test
	void recordsUnconditionalAutoConfigurations() {
		ConditionEvaluationReport report = report(Map.of(), Set.of(UNCONDITIONAL));

		Set<String> loaded = listener(report).detectLoadedAutoConfigurations(Set.of(UNCONDITIONAL));

		assertThat(loaded).containsExactly(UNCONDITIONAL);
	}

	/**
	 * Candidates rejected by Spring Boot's own import filters appear in neither
	 * collection, which correctly marks them unused.
	 */
	@Test
	void treatsCandidatesThatWereNeverEvaluatedAsUnused() {
		ConditionEvaluationReport report = report(Map.of(), Set.of());

		Set<String> loaded = listener(report).detectLoadedAutoConfigurations(Set.of(NEVER_EVALUATED));

		assertThat(loaded).isEmpty();
	}

	@Test
	void neverRecordsTheOptimizersOwnAutoConfigurationAsLoaded() {
		String own = AutoConfigurationOptimizerAutoConfiguration.class.getName();
		ConditionEvaluationReport report = report(Map.of(own, true), Set.of());

		Set<String> loaded = listener(report).detectLoadedAutoConfigurations(Set.of(own));

		assertThat(loaded).isEmpty();
	}

	@Test
	void loadsTheAutoConfigurationCandidatesFromTheClasspath() {
		assertThat(listener(report(Map.of(), Set.of())).loadAutoConfigurationCandidates())
			.contains(AutoConfigurationOptimizerAutoConfiguration.class.getName());
	}

	@Test
	void writesTheExcludedConfigurationsInTheCurrentFormat() throws Exception {
		TrainingRunApplicationListener listener = listener(report(Map.of(), Set.of()), "training.properties");

		listener.writeTrainingFile(List.of(NOT_MATCHED, NEVER_EVALUATED), Set.of(LOADED, NOT_MATCHED, NEVER_EVALUATED));

		Properties written = read("training.properties");
		assertThat(written.getProperty(TrainingFile.FORMAT_VERSION_KEY))
			.isEqualTo(String.valueOf(TrainingFile.FORMAT_VERSION));
		assertThat(written.getProperty(TrainingFile.EXCLUDED_CONFIGURATIONS_KEY).split(",")).extracting(String::trim)
			.containsExactlyInAnyOrder(NOT_MATCHED, NEVER_EVALUATED);
		assertThat(written.getProperty(TrainingFile.CANDIDATE_COUNT_KEY)).isEqualTo("3");
		assertThat(written.getProperty(TrainingFile.CANDIDATE_DIGEST_KEY))
			.isEqualTo(TrainingFile.candidateDigest(Set.of(LOADED, NOT_MATCHED, NEVER_EVALUATED)));
		assertThat(written.getProperty(TrainingFile.SPRING_BOOT_VERSION_KEY)).isNotBlank();
	}

	/**
	 * The header comment and the recorded timestamp used to call {@code now()}
	 * separately, so a single training run reported two different times.
	 */
	@Test
	void reportsOneConsistentTimestamp() throws Exception {
		TrainingRunApplicationListener listener = listener(report(Map.of(), Set.of()), "training.properties");

		listener.writeTrainingFile(List.of(), Set.of(LOADED));

		String timestamp = read("training.properties").getProperty(TrainingFile.TRAINING_TIMESTAMP_KEY);
		assertThat(Files.readString(this.tempDir.resolve("training.properties")))
			.contains("# Training run completed on: " + timestamp);
	}

	@Test
	void writesAnEmptyExclusionListWhenEveryCandidateWasUsed() throws Exception {
		TrainingRunApplicationListener listener = listener(report(Map.of(), Set.of()), "training.properties");

		listener.writeTrainingFile(List.of(), Set.of(LOADED));

		Properties written = read("training.properties");
		assertThat(written.getProperty(TrainingFile.EXCLUDED_CONFIGURATIONS_KEY)).isEmpty();
		assertThat(written.getProperty(TrainingFile.FORMAT_VERSION_KEY))
			.isEqualTo(String.valueOf(TrainingFile.FORMAT_VERSION));
	}

	@Test
	void leavesNoTemporaryFileBehind() throws Exception {
		TrainingRunApplicationListener listener = listener(report(Map.of(), Set.of()), "training.properties");

		listener.writeTrainingFile(List.of(NOT_MATCHED), Set.of(LOADED, NOT_MATCHED));

		try (var entries = Files.list(this.tempDir)) {
			assertThat(entries).containsExactly(this.tempDir.resolve("training.properties"));
		}
	}

	@Test
	void replacesAnExistingTrainingFile() throws Exception {
		Files.writeString(this.tempDir.resolve("training.properties"), "stale content");
		TrainingRunApplicationListener listener = listener(report(Map.of(), Set.of()), "training.properties");

		listener.writeTrainingFile(List.of(NOT_MATCHED), Set.of(LOADED, NOT_MATCHED));

		assertThat(Files.readString(this.tempDir.resolve("training.properties"))).doesNotContain("stale content");
	}

	@Test
	void rejectsAnOutputFileNameContainingDirectoryComponents() {
		TrainingRunApplicationListener listener = listener(report(Map.of(), Set.of()), "../escaped.properties");

		assertThatIllegalArgumentException().isThrownBy(() -> listener.writeTrainingFile(List.of(), Set.of(LOADED)))
			.withMessageContaining("simple filename");
	}

	private Properties read(String fileName) throws Exception {
		Properties properties = new Properties();
		try (var in = Files.newInputStream(this.tempDir.resolve(fileName))) {
			properties.load(in);
		}
		return properties;
	}

	private TrainingRunApplicationListener listener(ConditionEvaluationReport report) {
		return new TrainingRunApplicationListener(new AutoConfigurationOptimizerProperties(), report);
	}

	private TrainingRunApplicationListener listener(ConditionEvaluationReport report, String outputFile) {
		AutoConfigurationOptimizerProperties properties = new AutoConfigurationOptimizerProperties();
		properties.setOutputDirectory(this.tempDir.toString());
		properties.setOutputFile(outputFile);
		return new TrainingRunApplicationListener(properties, report);
	}

	private static ConditionEvaluationReport report(Map<String, Boolean> outcomesBySource,
			Set<String> unconditionalClasses) {
		ConditionEvaluationReport report = mock(ConditionEvaluationReport.class);
		Map<String, ConditionEvaluationReport.ConditionAndOutcomes> outcomes = new java.util.HashMap<>();
		outcomesBySource.forEach((source, fullMatch) -> {
			ConditionEvaluationReport.ConditionAndOutcomes conditionAndOutcomes = mock(
					ConditionEvaluationReport.ConditionAndOutcomes.class);
			when(conditionAndOutcomes.isFullMatch()).thenReturn(fullMatch);
			outcomes.put(source, conditionAndOutcomes);
		});
		when(report.getConditionAndOutcomesBySource()).thenReturn(outcomes);
		when(report.getUnconditionalClasses()).thenReturn(unconditionalClasses);
		return report;
	}

}
