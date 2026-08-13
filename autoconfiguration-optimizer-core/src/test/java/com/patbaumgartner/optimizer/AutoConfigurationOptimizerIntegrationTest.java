package com.patbaumgartner.optimizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying the autoconfiguration optimizer in training mode against a
 * real Spring Boot application context.
 */
@SpringBootTest(classes = AutoConfigurationOptimizerIntegrationTest.TestApp.class, properties = {
		"autoconfiguration.optimizer.training-run=true", "autoconfiguration.optimizer.exit-after-training=false" })
class AutoConfigurationOptimizerIntegrationTest {

	private static final String OUTPUT_FILE = "training.properties";

	/**
	 * The training run writes a file as a side effect of context startup. It is directed
	 * at a temporary directory so the build tree stays clean; the default output
	 * directory is the process working directory.
	 */
	@TempDir
	static Path outputDirectory;

	@Autowired
	private AutoConfigurationOptimizerProperties properties;

	@Autowired(required = false)
	private TrainingRunApplicationListener trainingRunApplicationListener;

	@DynamicPropertySource
	static void trainingOutputLocation(DynamicPropertyRegistry registry) {
		registry.add("autoconfiguration.optimizer.output-directory", outputDirectory::toString);
		registry.add("autoconfiguration.optimizer.output-file", () -> OUTPUT_FILE);
	}

	@Test
	void trainingRunListenerIsRegisteredWhenTrainingModeEnabled() {
		assertThat(trainingRunApplicationListener).isNotNull();
	}

	@Test
	void propertiesAreLoaded() {
		assertThat(properties).isNotNull();
		assertThat(properties.isTrainingRun()).isTrue();
		assertThat(properties.getOutputDirectory()).isEqualTo(outputDirectory.toString());
	}

	@Test
	void trainingRunWritesTheFileToTheConfiguredDirectory() {
		assertThat(outputDirectory.resolve(OUTPUT_FILE)).exists();
	}

	@Test
	void trainingRunLeavesNoTemporaryArtifactsBehind() throws Exception {
		try (var entries = Files.list(outputDirectory)) {
			assertThat(entries).containsExactly(outputDirectory.resolve(OUTPUT_FILE));
		}
	}

	/**
	 * Round trip over the real file: what the training run writes has to be exactly what
	 * the filter can read back. Asserting the writer and the reader separately let the
	 * two drift apart without any test noticing.
	 */
	@Test
	void theProducedTrainingFileDrivesTheFilter() throws Exception {
		Path classpathRoot = Files.createTempDirectory(outputDirectory.getParent(), "optimized-classpath");
		Path onClasspath = classpathRoot.resolve(TrainingFile.RESOURCE_LOCATION);
		Files.createDirectories(onClasspath.getParent());
		Files.copy(outputDirectory.resolve(OUTPUT_FILE), onClasspath);

		OptimizedAutoConfigurationImportFilter filter = new OptimizedAutoConfigurationImportFilter();
		filter.setEnvironment(new MockEnvironment());
		try (URLClassLoader classLoader = new URLClassLoader(new URL[] { classpathRoot.toUri().toURL() }, null)) {
			filter.setBeanClassLoader(classLoader);

			assertThat(filter.getExcludedConfigurations()).isNotNull()
				.contains(AutoConfigurationOptimizerAutoConfiguration.class.getName());
			assertThat(filter.match(new String[] { "com.example.IntroducedAfterTrainingAutoConfiguration" }, null))
				.containsExactly(true);
		}
	}

	@SpringBootApplication
	static class TestApp {

	}

}
