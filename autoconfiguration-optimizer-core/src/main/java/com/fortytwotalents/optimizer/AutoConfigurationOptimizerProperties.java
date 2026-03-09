package com.fortytwotalents.optimizer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Spring Boot Autoconfiguration Optimizer.
 *
 * <p>
 * These properties can be set via:
 * <ul>
 * <li>System properties (e.g.,
 * {@code -Dautoconfiguration.optimizer.training-run=true})</li>
 * <li>Environment variables (e.g.,
 * {@code AUTOCONFIGURATION_OPTIMIZER_TRAINING_RUN=true})</li>
 * <li>{@code application.properties} or {@code application.yml}</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "autoconfiguration.optimizer")
public class AutoConfigurationOptimizerProperties {

	/**
	 * Whether the autoconfiguration optimizer is enabled. When enabled and a training
	 * file is present, only the listed auto-configurations will be loaded. Default:
	 * {@code true}
	 */
	private boolean enabled = true;

	/**
	 * Whether this is a training run. During a training run, all auto-configurations are
	 * loaded and the ones that are active are recorded to a file. Use
	 * {@code TRAINING_RUN_JAVA_TOOL_OPTIONS} to set this during build. Default:
	 * {@code false}
	 */
	private boolean trainingRun = false;

	/**
	 * The name of the output file for the training run results. Default:
	 * {@code autoconfiguration-optimizer.properties}
	 */
	private String outputFile = "autoconfiguration-optimizer.properties";

	/**
	 * The output directory for the training run results file. Default: {@code .} (current
	 * working directory)
	 */
	private String outputDirectory = ".";

	/**
	 * Whether to exit the application after a training run completes. Useful when the
	 * application is started solely for the training run. Default: {@code false}
	 */
	private boolean exitAfterTraining = false;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isTrainingRun() {
		return trainingRun;
	}

	public void setTrainingRun(boolean trainingRun) {
		this.trainingRun = trainingRun;
	}

	public String getOutputFile() {
		return outputFile;
	}

	public void setOutputFile(String outputFile) {
		this.outputFile = outputFile;
	}

	public String getOutputDirectory() {
		return outputDirectory;
	}

	public void setOutputDirectory(String outputDirectory) {
		this.outputDirectory = outputDirectory;
	}

	public boolean isExitAfterTraining() {
		return exitAfterTraining;
	}

	public void setExitAfterTraining(boolean exitAfterTraining) {
		this.exitAfterTraining = exitAfterTraining;
	}

}
