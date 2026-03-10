package com.fortytwotalents.optimizer.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Injects the {@code autoconfiguration-optimizer-core} classes and META-INF resources
 * into the project's compile output directory ({@code target/classes}) before packaging.
 *
 * <p>
 * This goal should be configured to run in the {@code prepare-package} phase so that the
 * optimizer core classes are present in the build output whenever the project is
 * packaged. It only performs injection when a training output file already exists
 * (indicating that a training run has been completed), so it is safe to include in every
 * build.
 *
 * <p>
 * Together with the {@code train} goal, this eliminates the need to declare
 * {@code autoconfiguration-optimizer-core} as an explicit project dependency. The plugin
 * handles providing the core at training time and making it available at package time.
 *
 * <p>
 * Example {@code pom.xml} configuration: <pre>{@code
 * <plugin>
 *     <groupId>com.fortytwotalents</groupId>
 *     <artifactId>spring-boot-autoconfiguration-optimizer-maven-plugin</artifactId>
 *     <version>1.0.0</version>
 *     <executions>
 *         <execution>
 *             <goals>
 *                 <goal>train</goal>
 *                 <goal>inject</goal>
 *             </goals>
 *         </execution>
 *     </executions>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "inject", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresProject = true, threadSafe = true)
public class InjectMojo extends AbstractMojo {

	/**
	 * The Maven project.
	 */
	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	/**
	 * The directory that contains the training output file. Used to determine whether a
	 * training run has been performed. Defaults to {@code src/main/resources/META-INF}.
	 */
	@Parameter(property = "autoconfiguration.optimizer.targetDirectory",
			defaultValue = "${project.basedir}/src/main/resources/META-INF")
	private File targetDirectory;

	/**
	 * The name of the training output file that must exist for injection to be performed.
	 */
	@Parameter(property = "autoconfiguration.optimizer.outputFile",
			defaultValue = "autoconfiguration-optimizer.properties")
	private String outputFile;

	/**
	 * Skip core injection.
	 */
	@Parameter(property = "autoconfiguration.optimizer.skip", defaultValue = "false")
	private boolean skip;

	@Override
	public void execute() throws MojoExecutionException {
		if (skip) {
			getLog().info("Spring Boot Autoconfiguration Optimizer: Core injection skipped.");
			return;
		}

		// Only inject when a training file is present — this avoids unnecessary work on
		// projects that have not run training yet.
		Path trainingFile = targetDirectory.toPath().resolve(outputFile);
		if (!Files.exists(trainingFile)) {
			getLog().debug("Spring Boot Autoconfiguration Optimizer: No training file found at " + trainingFile
					+ ". Skipping core injection.");
			return;
		}

		Path coreJar = CoreInjector.findCoreJar();
		if (coreJar == null) {
			getLog().debug(
					"Spring Boot Autoconfiguration Optimizer: Core JAR not found as a file (running from directory). "
							+ "Skipping core injection.");
			return;
		}

		Path outputDir = Path.of(project.getBuild().getOutputDirectory());
		try {
			CoreInjector.injectCoreJarContents(coreJar, outputDir);
			getLog()
				.info("Spring Boot Autoconfiguration Optimizer: Core classes injected into build output: " + outputDir);
		}
		catch (IOException ex) {
			throw new MojoExecutionException("Failed to inject optimizer core classes into build output", ex);
		}
	}

}
