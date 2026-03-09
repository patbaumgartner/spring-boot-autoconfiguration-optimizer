package com.fortytwotalents.optimizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An {@link AutoConfigurationImportFilter} that restricts auto-configuration candidates
 * to only those recorded during a training run.
 *
 * <p>
 * This filter is activated when:
 * <ol>
 * <li>{@code autoconfiguration.optimizer.enabled} is {@code true} (default)</li>
 * <li>{@code autoconfiguration.optimizer.training-run} is {@code false} (default)</li>
 * <li>A file named {@code META-INF/autoconfiguration-optimizer.properties} exists on the
 * classpath</li>
 * </ol>
 *
 * <p>
 * Unlike the {@link OptimizedAutoConfigurationEnvironmentPostProcessor} approach of
 * setting {@code spring.autoconfigure.exclude}, this filter operates directly inside
 * Spring Boot's {@code AutoConfigurationImportSelector} pipeline. As a result:
 * <ul>
 * <li>The list of auto-configuration candidates is loaded only once by Spring Boot (no
 * duplicate loading)</li>
 * <li>Filtering is done via efficient {@code boolean[]} operations on the candidate
 * string array — no comma-separated string building or environment manipulation</li>
 * <li>The filter runs at the earliest possible point, before auto-configuration classes
 * are loaded or their conditions evaluated</li>
 * </ul>
 *
 * @see TrainingRunApplicationListener
 * @see AutoConfigurationOptimizerProperties
 */
public class OptimizedAutoConfigurationImportFilter
		implements AutoConfigurationImportFilter, BeanClassLoaderAware, EnvironmentAware {

	private static final Logger log = LoggerFactory.getLogger(OptimizedAutoConfigurationImportFilter.class);

	private ClassLoader classLoader;

	private Environment environment;

	private boolean initialized;

	private Set<String> allowedConfigurations;

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata autoConfigurationMetadata) {
		boolean[] result = new boolean[autoConfigurationClasses.length];

		if (!isFilterActive()) {
			Arrays.fill(result, true);
			return result;
		}

		Set<String> allowed = getAllowedConfigurations();
		if (allowed == null) {
			// No training file found — allow everything
			Arrays.fill(result, true);
			return result;
		}

		int filteredCount = 0;
		for (int i = 0; i < autoConfigurationClasses.length; i++) {
			String config = autoConfigurationClasses[i];
			// null means an earlier filter already removed this candidate; pass it
			// through
			result[i] = config == null || allowed.contains(config);
			if (!result[i]) {
				filteredCount++;
			}
		}

		if (filteredCount > 0) {
			log.info(
					"Spring Boot Autoconfiguration Optimizer: Filtered {} / {} auto-configurations using training set of {} entries.",
					filteredCount, autoConfigurationClasses.length, allowed.size());
		}

		return result;
	}

	private boolean isFilterActive() {
		if (environment == null) {
			return false;
		}
		boolean enabled = environment.getProperty("autoconfiguration.optimizer.enabled", Boolean.class, true);
		boolean trainingRun = environment.getProperty("autoconfiguration.optimizer.training-run", Boolean.class, false);
		if (!enabled) {
			log.debug("Spring Boot Autoconfiguration Optimizer: Optimization disabled");
			return false;
		}
		if (trainingRun) {
			log.debug("Spring Boot Autoconfiguration Optimizer: Skipping optimization (training run active)");
			return false;
		}
		return true;
	}

	/**
	 * Returns the set of allowed auto-configuration class names from the training file,
	 * loading it lazily on first access. Returns {@code null} when no training file is
	 * present, which causes the filter to allow all candidates.
	 */
	Set<String> getAllowedConfigurations() {
		if (!initialized) {
			allowedConfigurations = loadAllowedConfigurations();
			initialized = true;
		}
		return allowedConfigurations;
	}

	private Set<String> loadAllowedConfigurations() {
		ClassLoader loader = this.classLoader != null ? this.classLoader
				: Thread.currentThread().getContextClassLoader();
		Resource resource = new ClassPathResource(
				OptimizedAutoConfigurationEnvironmentPostProcessor.OPTIMIZER_PROPERTIES_FILE, loader);

		if (!resource.exists()) {
			log.debug(
					"Spring Boot Autoconfiguration Optimizer: No training file found at classpath:{}. "
							+ "Running with all auto-configurations.",
					OptimizedAutoConfigurationEnvironmentPostProcessor.OPTIMIZER_PROPERTIES_FILE);
			return null;
		}

		try {
			Properties props = new Properties();
			try (var inputStream = resource.getInputStream()) {
				props.load(inputStream);
			}

			String loadedConfigsValue = props
				.getProperty(OptimizedAutoConfigurationEnvironmentPostProcessor.LOADED_CONFIGURATIONS_KEY);

			if (loadedConfigsValue == null || loadedConfigsValue.isBlank()) {
				log.warn(
						"Spring Boot Autoconfiguration Optimizer: Training file exists but contains no loaded configurations. "
								+ "Running with all auto-configurations.");
				return null;
			}

			Set<String> allowed = Arrays.stream(loadedConfigsValue.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.collect(Collectors.toSet());

			log.debug("Spring Boot Autoconfiguration Optimizer: Loaded {} allowed configurations from training file.",
					allowed.size());
			return allowed;
		}
		catch (IOException e) {
			log.error("Spring Boot Autoconfiguration Optimizer: Failed to read training file, "
					+ "running with all auto-configurations", e);
			return null;
		}
	}

}
