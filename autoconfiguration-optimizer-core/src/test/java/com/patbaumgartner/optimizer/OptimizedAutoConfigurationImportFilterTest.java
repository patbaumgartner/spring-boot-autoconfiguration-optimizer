package com.patbaumgartner.optimizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizedAutoConfigurationImportFilterTest {

	private static final String FOO = "com.example.FooAutoConfiguration";

	private static final String BAR = "com.example.BarAutoConfiguration";

	private static final String BAZ = "com.example.BazAutoConfiguration";

	@TempDir
	Path tempDir;

	@Test
	void allowsEverythingWhenNoEnvironmentIsAvailable() {
		OptimizedAutoConfigurationImportFilter filter = new OptimizedAutoConfigurationImportFilter();

		assertThat(filter.match(new String[] { FOO, BAR }, null)).containsOnly(true);
	}

	@Test
	void allowsEverythingWhenDisabled() throws Exception {
		OptimizedAutoConfigurationImportFilter filter = filterUsing(trainingFile(BAR));
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty("autoconfiguration.optimizer.enabled", "false");
		filter.setEnvironment(environment);

		assertThat(filter.match(new String[] { FOO, BAR }, null)).containsOnly(true);
	}

	@Test
	void allowsEverythingDuringATrainingRun() throws Exception {
		OptimizedAutoConfigurationImportFilter filter = filterUsing(trainingFile(BAR));
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty("autoconfiguration.optimizer.training-run", "true");
		filter.setEnvironment(environment);

		assertThat(filter.match(new String[] { FOO, BAR }, null)).containsOnly(true);
	}

	@Test
	void allowsEverythingWhenNoTrainingFileIsOnTheClasspath() throws Exception {
		OptimizedAutoConfigurationImportFilter filter = filterUsing(emptyClasspathRoot());

		assertThat(filter.match(new String[] { FOO, BAR }, null)).containsOnly(true);
		assertThat(filter.getExcludedConfigurations()).isNull();
	}

	@Test
	void skipsOnlyTheRecordedExclusions() throws Exception {
		OptimizedAutoConfigurationImportFilter filter = filterUsing(trainingFile(BAR, BAZ));

		boolean[] result = filter.match(new String[] { FOO, BAR, BAZ }, null);

		assertThat(result).containsExactly(true, false, false);
	}

	/**
	 * The reason exclusions are recorded rather than inclusions: an auto-configuration
	 * introduced by a dependency added after the training run is unknown to the training
	 * file and must still load, otherwise adding a starter would silently do nothing.
	 */
	@Test
	void allowsCandidatesThatDidNotExistAtTrainingTime() throws Exception {
		OptimizedAutoConfigurationImportFilter filter = filterUsing(trainingFile(BAR));

		boolean[] result = filter.match(new String[] { "com.example.AddedLaterAutoConfiguration", BAR }, null);

		assertThat(result).containsExactly(true, false);
	}

	@Test
	void allowsProgrammaticallyImportedConfigurations() throws Exception {
		OptimizedAutoConfigurationImportFilter filter = filterUsing(trainingFile(BAR));

		boolean[] result = filter.match(new String[] { "com.example.SomeAutoConfiguration$Inner", BAR }, null);

		assertThat(result).containsExactly(true, false);
	}

	@Test
	void passesNullCandidatesThroughUnchanged() throws Exception {
		OptimizedAutoConfigurationImportFilter filter = filterUsing(trainingFile(BAR));

		boolean[] result = filter.match(new String[] { null, BAR }, null);

		assertThat(result).containsExactly(true, false);
	}

	@Test
	void treatsAnEmptyExclusionListAsNothingToSkip() throws Exception {
		OptimizedAutoConfigurationImportFilter filter = filterUsing(trainingFile());

		assertThat(filter.getExcludedConfigurations()).isEmpty();
		assertThat(filter.match(new String[] { FOO, BAR }, null)).containsOnly(true);
	}

	/**
	 * A file written by a different optimizer version may assign different meaning to the
	 * recorded set, so applying it could silently remove auto-configurations the
	 * application needs. Refusing to optimize is the safe interpretation.
	 */
	@Test
	void ignoresATrainingFileWithAnUnknownFormatVersion() throws Exception {
		Path root = classpathRootWith("newer", TrainingFile.FORMAT_VERSION_KEY + "="
				+ (TrainingFile.FORMAT_VERSION + 1) + "\n" + TrainingFile.EXCLUDED_CONFIGURATIONS_KEY + "=" + BAR);
		OptimizedAutoConfigurationImportFilter filter = filterUsing(root);

		assertThat(filter.getExcludedConfigurations()).isNull();
		assertThat(filter.match(new String[] { FOO, BAR }, null)).containsOnly(true);
	}

	/**
	 * Training files produced before the format was versioned recorded the loaded set
	 * instead of the excluded set. Reading one as if it were an exclusion list would skip
	 * exactly the auto-configurations the application actually uses.
	 */
	@Test
	void ignoresALegacyTrainingFileThatRecordedLoadedConfigurations() throws Exception {
		Path root = classpathRootWith("legacy",
				"autoconfiguration.optimizer.loaded-configurations=" + FOO + "," + BAR);
		OptimizedAutoConfigurationImportFilter filter = filterUsing(root);

		assertThat(filter.getExcludedConfigurations()).isNull();
		assertThat(filter.match(new String[] { FOO, BAR }, null)).containsOnly(true);
	}

	/**
	 * Two training files means two applications' exclusions; resolving a single resource
	 * would pick one by classpath order and apply the wrong application's optimization.
	 */
	@Test
	void refusesToOptimizeWhenSeveralTrainingFilesAreOnTheClasspath() throws Exception {
		Path first = classpathRootWith("first", trainingFileContent(BAR));
		Path second = classpathRootWith("second", trainingFileContent(BAZ));
		OptimizedAutoConfigurationImportFilter filter = filterUsing(first, second);

		assertThat(filter.getExcludedConfigurations()).isNull();
		assertThat(filter.match(new String[] { FOO, BAR, BAZ }, null)).containsOnly(true);
	}

	@Test
	void readsTheTrainingFileOnlyOnce() throws Exception {
		OptimizedAutoConfigurationImportFilter filter = filterUsing(trainingFile(BAR));

		Set<String> first = filter.getExcludedConfigurations();
		Set<String> second = filter.getExcludedConfigurations();

		assertThat(first).isSameAs(second);
	}

	private static String trainingFileContent(String... excluded) {
		return TrainingFile.FORMAT_VERSION_KEY + "=" + TrainingFile.FORMAT_VERSION + "\n"
				+ TrainingFile.EXCLUDED_CONFIGURATIONS_KEY + "=" + String.join(",", excluded);
	}

	private Path trainingFile(String... excluded) throws Exception {
		return classpathRootWith("classpath", trainingFileContent(excluded));
	}

	private Path classpathRootWith(String name, String content) throws Exception {
		Path root = this.tempDir.resolve(name);
		Path file = root.resolve(TrainingFile.RESOURCE_LOCATION);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		return root;
	}

	private Path emptyClasspathRoot() throws Exception {
		Path root = this.tempDir.resolve("empty");
		Files.createDirectories(root);
		return root;
	}

	private OptimizedAutoConfigurationImportFilter filterUsing(Path... classpathRoots) throws Exception {
		List<URL> urls = new ArrayList<>();
		for (Path root : classpathRoots) {
			urls.add(root.toUri().toURL());
		}
		OptimizedAutoConfigurationImportFilter filter = new OptimizedAutoConfigurationImportFilter();
		filter.setBeanClassLoader(new URLClassLoader(urls.toArray(URL[]::new), null));
		filter.setEnvironment(new MockEnvironment());
		return filter;
	}

}
