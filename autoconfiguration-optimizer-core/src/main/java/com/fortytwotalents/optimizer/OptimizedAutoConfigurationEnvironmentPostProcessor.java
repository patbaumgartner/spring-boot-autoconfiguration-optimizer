package com.fortytwotalents.optimizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An {@link EnvironmentPostProcessor} that reads the optimizer properties file and
 * configures Spring Boot to exclude auto-configurations that were not used during the
 * training run.
 *
 * <p>
 * This processor is activated only when:
 * <ol>
 * <li>{@code autoconfiguration.optimizer.enabled} is {@code true} (default)</li>
 * <li>{@code autoconfiguration.optimizer.training-run} is {@code false} (default)</li>
 * <li>A file named {@code META-INF/autoconfiguration-optimizer.properties} exists on the
 * classpath</li>
 * </ol>
 *
 * <p>
 * The processor reads the list of loaded auto-configurations from the training file and
 * sets {@code spring.autoconfigure.exclude} to exclude all auto-configurations that are
 * available on the classpath but were not present in the training file.
 *
 * @see TrainingRunApplicationListener
 * @see AutoConfigurationOptimizerProperties
 */
public class OptimizedAutoConfigurationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	private static final Logger log = LoggerFactory.getLogger(OptimizedAutoConfigurationEnvironmentPostProcessor.class);

	static final String OPTIMIZER_PROPERTIES_FILE = "META-INF/autoconfiguration-optimizer.properties";
	static final String LOADED_CONFIGURATIONS_KEY = "autoconfiguration.optimizer.loaded-configurations";
	static final String PROPERTY_SOURCE_NAME = "autoconfigurationOptimizerExclusions";
	static final String EXCLUDE_PROPERTY = "spring.autoconfigure.exclude";

	static final String AUTO_CONFIGURATION_IMPORTS_LOCATION = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 10;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		// Skip if this is a training run
		if (isTrainingRun(environment)) {
			log.debug("Spring Boot Autoconfiguration Optimizer: Skipping optimization (training run active)");
			return;
		}

		// Skip if optimizer is disabled
		if (!isEnabled(environment)) {
			log.debug("Spring Boot Autoconfiguration Optimizer: Optimization disabled");
			return;
		}

		// Load the optimizer properties file
		Resource optimizerResource = new ClassPathResource(OPTIMIZER_PROPERTIES_FILE);
		if (!optimizerResource.exists()) {
			log.debug("Spring Boot Autoconfiguration Optimizer: No training file found at classpath:{}. "
					+ "Running with all auto-configurations.", OPTIMIZER_PROPERTIES_FILE);
			return;
		}

		try {
			Properties optimizerProperties = loadProperties(optimizerResource);
			String loadedConfigsValue = optimizerProperties.getProperty(LOADED_CONFIGURATIONS_KEY);

			if (loadedConfigsValue == null || loadedConfigsValue.isBlank()) {
				log.warn(
						"Spring Boot Autoconfiguration Optimizer: Training file exists but contains no loaded configurations. "
								+ "Skipping optimization.");
				return;
			}

			Set<String> loadedConfigs = parseConfigurationList(loadedConfigsValue);
			List<String> allAvailableConfigs = loadAllAvailableAutoConfigurations();

			if (allAvailableConfigs.isEmpty()) {
				log.warn("Spring Boot Autoconfiguration Optimizer: No auto-configuration imports found on classpath. "
						+ "Skipping optimization.");
				return;
			}

			List<String> toExclude = allAvailableConfigs.stream()
				.filter(config -> !loadedConfigs.contains(config))
				.sorted()
				.collect(Collectors.toList());

			if (toExclude.isEmpty()) {
				log.debug(
						"Spring Boot Autoconfiguration Optimizer: All available auto-configurations are in the training set. "
								+ "No exclusions applied.");
				return;
			}

			applyExclusions(environment, toExclude);
			log.info(
					"Spring Boot Autoconfiguration Optimizer: Applied {} exclusion(s). "
							+ "Loaded {} / {} available auto-configurations.",
					toExclude.size(), loadedConfigs.size(), allAvailableConfigs.size());

		}
		catch (IOException e) {
			log.error("Spring Boot Autoconfiguration Optimizer: Failed to read training file, "
					+ "running with all auto-configurations", e);
		}
	}

	private boolean isTrainingRun(ConfigurableEnvironment environment) {
		return environment.getProperty("autoconfiguration.optimizer.training-run", Boolean.class, false);
	}

	private boolean isEnabled(ConfigurableEnvironment environment) {
		return environment.getProperty("autoconfiguration.optimizer.enabled", Boolean.class, true);
	}

	private Properties loadProperties(Resource resource) throws IOException {
		Properties props = new Properties();
		try (var inputStream = resource.getInputStream()) {
			props.load(inputStream);
		}
		return props;
	}

	Set<String> parseConfigurationList(String value) {
		return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
	}

	List<String> loadAllAvailableAutoConfigurations() throws IOException {
		List<String> autoConfigs = new ArrayList<>();
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		if (classLoader == null) {
			classLoader = OptimizedAutoConfigurationEnvironmentPostProcessor.class.getClassLoader();
		}
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
		return autoConfigs;
	}

	void applyExclusions(ConfigurableEnvironment environment, List<String> toExclude) {
		// Build the exclusion string, preserving any existing exclusions
		String existingExclusions = environment.getProperty(EXCLUDE_PROPERTY, "");
		Set<String> allExclusions = new HashSet<>();

		if (!existingExclusions.isBlank()) {
			allExclusions.addAll(Arrays.asList(existingExclusions.split(",")));
		}
		allExclusions.addAll(toExclude);

		String exclusionValue = String.join(",", allExclusions);

		MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME,
				Map.of(EXCLUDE_PROPERTY, exclusionValue));

		// Add first so the combined exclusion list takes effect; user can override via
		// higher-priority sources
		environment.getPropertySources().addFirst(propertySource);
	}

}
