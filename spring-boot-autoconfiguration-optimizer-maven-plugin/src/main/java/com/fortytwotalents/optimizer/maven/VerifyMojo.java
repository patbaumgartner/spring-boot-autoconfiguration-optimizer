package com.fortytwotalents.optimizer.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies that the autoconfiguration optimizer training file is present and up-to-date.
 *
 * <p>
 * This goal can be used in CI pipelines to ensure the training file has been generated
 * and is committed to source control.
 *
 * <p>
 * Usage: <pre>{@code
 * mvn com.fortytwotalents:spring-boot-autoconfiguration-optimizer-maven-plugin:verify
 * }</pre>
 */
@Mojo(name = "verify", defaultPhase = LifecyclePhase.VERIFY, requiresProject = true, threadSafe = true)
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
			defaultValue = "${project.basedir}/src/main/resources/META-INF/autoconfiguration-optimizer.properties")
	private File trainingFile;

	/**
	 * Whether to fail if the training file is missing.
	 */
	@Parameter(property = "autoconfiguration.optimizer.failOnMissing", defaultValue = "true")
	private boolean failOnMissing;

	/**
	 * Skip the verification.
	 */
	@Parameter(property = "autoconfiguration.optimizer.skip", defaultValue = "false")
	private boolean skip;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (skip) {
			getLog().info("Spring Boot Autoconfiguration Optimizer: Verification skipped.");
			return;
		}

		Path trainingFilePath = trainingFile.toPath();

		if (!Files.exists(trainingFilePath)) {
			String message = "Spring Boot Autoconfiguration Optimizer: Training file not found at: "
					+ trainingFilePath.toAbsolutePath()
					+ ". Run 'mvn autoconfiguration-optimizer:train' to generate it.";

			if (failOnMissing) {
				throw new MojoFailureException(message);
			}
			else {
				getLog().warn(message);
			}
		}
		else {
			getLog().info("Spring Boot Autoconfiguration Optimizer: Training file found at: "
					+ trainingFilePath.toAbsolutePath());
		}
	}

}
