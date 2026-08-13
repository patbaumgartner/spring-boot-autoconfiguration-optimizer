package com.patbaumgartner.optimizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An {@link AutoConfigurationImportFilter} that skips the auto-configurations recorded as
 * unused during a training run.
 *
 * <p>
 * This filter is activated when:
 * <ol>
 * <li>{@code autoconfiguration.optimizer.enabled} is {@code true} (default)</li>
 * <li>{@code autoconfiguration.optimizer.training-run} is {@code false} (default)</li>
 * <li>A readable, current-format training file exists on the classpath at
 * {@value TrainingFile#RESOURCE_LOCATION}</li>
 * </ol>
 *
 * <p>
 * Unlike the {@code spring.autoconfigure.exclude} approach of setting exclusions via an
 * {@code EnvironmentPostProcessor}, this filter operates directly inside Spring Boot's
 * {@code AutoConfigurationImportSelector} pipeline. As a result:
 * <ul>
 * <li>The list of auto-configuration candidates is loaded only once by Spring Boot (no
 * duplicate loading)</li>
 * <li>Filtering is done via efficient {@code boolean[]} operations on the candidate
 * string array - no comma-separated string building or environment manipulation</li>
 * <li>The filter runs at the earliest possible point, before auto-configuration classes
 * are loaded or their conditions evaluated</li>
 * </ul>
 *
 * <p>
 * Because the training file records exclusions rather than inclusions, the filter never
 * needs to know the full candidate set. Anything it was not explicitly told to skip is
 * allowed, which covers both candidates introduced after training and the configurations
 * Spring Boot imports programmatically (for example
 * {@code DataSourceConfiguration$Hikari}), which are passed to import filters without
 * being registered auto-configuration candidates.
 *
 * <p>
 * Every failure mode - a missing file, an unreadable file, an unrecognised format
 * version, or more than one training file on the classpath - degrades to allowing all
 * auto-configurations, so a broken optimizer costs startup time rather than correctness.
 *
 * @see TrainingRunApplicationListener
 * @see TrainingFile
 */
public class OptimizedAutoConfigurationImportFilter
		implements AutoConfigurationImportFilter, BeanClassLoaderAware, EnvironmentAware {

	private static final Logger log = LoggerFactory.getLogger(OptimizedAutoConfigurationImportFilter.class);

	private ClassLoader classLoader;

	private Environment environment;

	private boolean initialized;

	private Set<String> excludedConfigurations;

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

		Set<String> excluded = isFilterActive() ? getExcludedConfigurations() : null;
		if (excluded == null || excluded.isEmpty()) {
			Arrays.fill(result, true);
			return result;
		}

		int skipped = 0;
		for (int i = 0; i < autoConfigurationClasses.length; i++) {
			String candidate = autoConfigurationClasses[i];
			// A null entry means an earlier filter already removed this candidate.
			result[i] = candidate == null || !excluded.contains(candidate);
			if (!result[i]) {
				skipped++;
			}
		}

		if (skipped > 0) {
			log.debug("Spring Boot Autoconfiguration Optimizer: Skipped {} of {} auto-configuration candidates.",
					skipped, autoConfigurationClasses.length);
		}

		return result;
	}

	private boolean isFilterActive() {
		if (this.environment == null) {
			return false;
		}
		if (!this.environment.getProperty("autoconfiguration.optimizer.enabled", Boolean.class, true)) {
			log.debug("Spring Boot Autoconfiguration Optimizer: Optimization disabled");
			return false;
		}
		if (this.environment.getProperty("autoconfiguration.optimizer.training-run", Boolean.class, false)) {
			log.debug("Spring Boot Autoconfiguration Optimizer: Skipping optimization (training run active)");
			return false;
		}
		return true;
	}

	/**
	 * Returns the auto-configuration class names to skip, loading the training file
	 * lazily on first access. Returns {@code null} when no usable training file is
	 * present, which causes the filter to allow all candidates.
	 * @return the excluded class names, or {@code null} to disable filtering
	 */
	Set<String> getExcludedConfigurations() {
		if (!this.initialized) {
			this.excludedConfigurations = loadExcludedConfigurations();
			this.initialized = true;
		}
		return this.excludedConfigurations;
	}

	private Set<String> loadExcludedConfigurations() {
		ClassLoader loader = (this.classLoader != null) ? this.classLoader
				: Thread.currentThread().getContextClassLoader();

		List<URL> resources;
		try {
			resources = Collections.list(loader.getResources(TrainingFile.RESOURCE_LOCATION));
		}
		catch (IOException ex) {
			log.error("Spring Boot Autoconfiguration Optimizer: Failed to look up classpath:{}, "
					+ "running with all auto-configurations.", TrainingFile.RESOURCE_LOCATION, ex);
			return null;
		}

		if (resources.isEmpty()) {
			log.debug("Spring Boot Autoconfiguration Optimizer: No training file found at classpath:{}. "
					+ "Running with all auto-configurations.", TrainingFile.RESOURCE_LOCATION);
			return null;
		}
		if (resources.size() > 1) {
			// Picking one arbitrarily would apply one application's exclusions to
			// another, so refuse to optimize rather than guess.
			log.warn(
					"Spring Boot Autoconfiguration Optimizer: Found {} training files on the classpath ({}). "
							+ "Exactly one is required; running with all auto-configurations.",
					resources.size(), resources);
			return null;
		}

		URL resource = resources.get(0);
		Properties properties = new Properties();
		try (InputStream inputStream = resource.openStream()) {
			properties.load(inputStream);
		}
		catch (IOException ex) {
			log.error("Spring Boot Autoconfiguration Optimizer: Failed to read training file {}, "
					+ "running with all auto-configurations.", resource, ex);
			return null;
		}

		String formatVersion = properties.getProperty(TrainingFile.FORMAT_VERSION_KEY);
		if (!String.valueOf(TrainingFile.FORMAT_VERSION).equals(formatVersion)) {
			log.warn("Spring Boot Autoconfiguration Optimizer: Training file {} declares format version {} but this "
					+ "optimizer requires version {}. Re-run the training goal; running with all "
					+ "auto-configurations.", resource, formatVersion, TrainingFile.FORMAT_VERSION);
			return null;
		}

		Set<String> excluded = parseExcludedConfigurations(
				properties.getProperty(TrainingFile.EXCLUDED_CONFIGURATIONS_KEY));
		if (excluded.isEmpty()) {
			log.info("Spring Boot Autoconfiguration Optimizer: Training file records no exclusions; "
					+ "running with all auto-configurations.");
		}
		else {
			log.info(
					"Spring Boot Autoconfiguration Optimizer: Skipping {} auto-configurations that were not used "
							+ "during the training run of {}.",
					excluded.size(), properties.getProperty(TrainingFile.TRAINING_TIMESTAMP_KEY, "an earlier build"));
		}
		return excluded;
	}

	private static Set<String> parseExcludedConfigurations(String value) {
		if (value == null || value.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(value.split(","))
			.map(String::trim)
			.filter((entry) -> !entry.isEmpty())
			.collect(Collectors.toUnmodifiableSet());
	}

}
