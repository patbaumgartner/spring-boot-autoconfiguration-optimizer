package com.patbaumgartner.optimizer.maven;

import com.patbaumgartner.optimizer.TrainingFile;
import com.patbaumgartner.optimizer.build.AutoConfigurationCandidates;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Verifies that the training file is present, readable by this version of the optimizer,
 * and still matches the project's auto-configuration candidates.
 *
 * <p>
 * The last check is the one that matters in CI: it catches a dependency added or removed
 * after the training run, which leaves the recorded exclusions describing a classpath
 * that no longer exists.
 *
 * <p>
 * Usage:
 *
 * <pre>{@code mvn com.patbaumgartner:spring-boot-autoconfiguration-optimizer-maven-plugin:verify }</pre>
 */
@Mojo(name = "verify", defaultPhase = LifecyclePhase.VERIFY, requiresDependencyResolution = ResolutionScope.RUNTIME,
		requiresProject = true, threadSafe = true)
public class VerifyMojo extends AbstractMojo {

	/**
	 * The Maven project.
	 */
	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	/**
	 * The expected location of the training properties file.
	 */
	@Parameter(property = "autoconfiguration.optimizer.trainingFile",
			defaultValue = "${project.build.outputDirectory}/META-INF/autoconfiguration-optimizer.properties")
	private File trainingFile;

	/**
	 * Whether to fail if the training file is missing.
	 */
	@Parameter(property = "autoconfiguration.optimizer.failOnMissing", defaultValue = "true")
	private boolean failOnMissing;

	/**
	 * Whether to fail if the training file no longer matches the project's
	 * auto-configuration candidates.
	 */
	@Parameter(property = "autoconfiguration.optimizer.failOnStale", defaultValue = "true")
	private boolean failOnStale;

	/**
	 * Skip the verification.
	 */
	@Parameter(property = "autoconfiguration.optimizer.skip", defaultValue = "false")
	private boolean skip;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (this.skip) {
			getLog().info("Spring Boot Autoconfiguration Optimizer: Verification skipped.");
			return;
		}

		Path trainingFilePath = this.trainingFile.toPath();
		if (!Files.exists(trainingFilePath)) {
			report(this.failOnMissing,
					"Spring Boot Autoconfiguration Optimizer: Training file not found at: "
							+ trainingFilePath.toAbsolutePath()
							+ ". Run 'mvn autoconfiguration-optimizer:train' to generate it.");
			return;
		}

		Properties properties = read(trainingFilePath);

		String formatVersion = properties.getProperty(TrainingFile.FORMAT_VERSION_KEY);
		if (!String.valueOf(TrainingFile.FORMAT_VERSION).equals(formatVersion)) {
			throw new MojoFailureException("Spring Boot Autoconfiguration Optimizer: Training file "
					+ trainingFilePath.toAbsolutePath() + " declares format version " + formatVersion
					+ " but this plugin produces version " + TrainingFile.FORMAT_VERSION
					+ ". Re-run 'mvn autoconfiguration-optimizer:train'.");
		}

		Set<String> candidates = scanCandidates();
		String currentDigest = TrainingFile.candidateDigest(candidates);
		if (!currentDigest.equals(properties.getProperty(TrainingFile.CANDIDATE_DIGEST_KEY))) {
			report(this.failOnStale, "Spring Boot Autoconfiguration Optimizer: Training file "
					+ trainingFilePath.toAbsolutePath() + " is stale. It was recorded against "
					+ properties.getProperty(TrainingFile.CANDIDATE_COUNT_KEY, "an unknown number of")
					+ " auto-configuration candidates, but this project now has " + candidates.size()
					+ ". Re-run 'mvn autoconfiguration-optimizer:train' so the recorded exclusions match the current "
					+ "dependencies.");
			return;
		}

		getLog().info("Spring Boot Autoconfiguration Optimizer: Training file is up to date (" + candidates.size()
				+ " auto-configuration candidates).");
	}

	private void report(boolean fail, String message) throws MojoFailureException {
		if (fail) {
			throw new MojoFailureException(message);
		}
		getLog().warn(message);
	}

	private Properties read(Path trainingFilePath) throws MojoExecutionException {
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(trainingFilePath)) {
			properties.load(in);
		}
		catch (IOException ex) {
			throw new MojoExecutionException("Failed to read training file: " + trainingFilePath, ex);
		}
		return properties;
	}

	private Set<String> scanCandidates() throws MojoExecutionException {
		try {
			List<String> runtimeClasspath = this.project.getRuntimeClasspathElements();
			return AutoConfigurationCandidates.scan(runtimeClasspath.stream().map(Path::of).toList());
		}
		catch (Exception ex) {
			throw new MojoExecutionException(
					"Failed to read auto-configuration candidates from the project's runtime classpath", ex);
		}
	}

}
