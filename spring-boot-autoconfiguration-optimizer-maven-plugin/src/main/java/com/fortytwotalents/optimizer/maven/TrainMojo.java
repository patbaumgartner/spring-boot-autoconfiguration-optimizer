package com.fortytwotalents.optimizer.maven;

import org.apache.maven.artifact.Artifact;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs a Spring Boot application training run to detect which auto-configurations are
 * loaded.
 *
 * <p>
 * This goal starts the Spring Boot application with the training flag enabled
 * ({@code autoconfiguration.optimizer.training-run=true}), waits for it to complete, and
 * copies the generated properties file to the target resources directory.
 *
 * <p>
 * The {@code autoconfiguration-optimizer-core} JAR is automatically added to the training
 * classpath by the plugin, so it does <em>not</em> need to be declared as a project
 * dependency. After training, the core classes are also injected into the project's
 * compile output directory ({@code target/classes}) so that they are included when the
 * application is packaged. To ensure the core classes survive a {@code mvn clean},
 * configure the {@code inject} goal to run during the {@code prepare-package} phase.
 *
 * <p>
 * Usage in {@code pom.xml}: <pre>{@code
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
 *
 * <p>
 * Or run from the command line: <pre>{@code
 * mvn com.fortytwotalents:spring-boot-autoconfiguration-optimizer-maven-plugin:train
 * }</pre>
 */
@Mojo(name = "train", defaultPhase = LifecyclePhase.INTEGRATION_TEST,
		requiresDependencyResolution = ResolutionScope.TEST, requiresProject = true, threadSafe = true)
public class TrainMojo extends AbstractMojo {

	/**
	 * The Maven project.
	 */
	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	/**
	 * The main class to run. If not specified, it will be auto-detected from the Spring
	 * Boot plugin configuration or {@code BOOT-INF/classes} in the JAR.
	 */
	@Parameter(property = "autoconfiguration.optimizer.mainClass")
	private String mainClass;

	/**
	 * Additional JVM arguments to pass to the training run.
	 */
	@Parameter(property = "autoconfiguration.optimizer.jvmArguments")
	private List<String> jvmArguments = new ArrayList<>();

	/**
	 * The training run timeout in seconds.
	 */
	@Parameter(property = "autoconfiguration.optimizer.timeout", defaultValue = "120")
	private int timeout;

	/**
	 * The directory where the generated properties file will be copied. Defaults to
	 * {@code src/main/resources/META-INF/}.
	 */
	@Parameter(property = "autoconfiguration.optimizer.targetDirectory",
			defaultValue = "${project.basedir}/src/main/resources/META-INF")
	private File targetDirectory;

	/**
	 * The name of the generated properties file.
	 */
	@Parameter(property = "autoconfiguration.optimizer.outputFile",
			defaultValue = "autoconfiguration-optimizer.properties")
	private String outputFile;

	/**
	 * The working directory for the training run. The generated file will be written here
	 * before being copied to targetDirectory.
	 */
	@Parameter(property = "autoconfiguration.optimizer.workingDirectory", defaultValue = "${project.build.directory}")
	private File workingDirectory;

	/**
	 * Skip the training run.
	 */
	@Parameter(property = "autoconfiguration.optimizer.skip", defaultValue = "false")
	private boolean skip;

	/**
	 * The Spring Boot executable JAR to run for the training. If not specified, the
	 * plugin will attempt to run the main class directly.
	 */
	@Parameter(property = "autoconfiguration.optimizer.jar")
	private File jar;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (skip) {
			getLog().info("Spring Boot Autoconfiguration Optimizer: Training run skipped.");
			return;
		}

		getLog().info("Spring Boot Autoconfiguration Optimizer: Starting training run...");

		Path outputFilePath = workingDirectory.toPath().resolve(outputFile);
		// Delete any existing training file to ensure a fresh run
		try {
			Files.deleteIfExists(outputFilePath);
		}
		catch (IOException e) {
			throw new MojoExecutionException("Failed to delete existing training file", e);
		}

		List<String> command = buildCommand(outputFilePath);
		getLog().debug("Training run command: " + String.join(" ", command));

		try {
			Process process = new ProcessBuilder(command).directory(workingDirectory)
				.redirectErrorStream(true)
				.inheritIO()
				.start();

			boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new MojoExecutionException("Training run timed out after " + timeout + " seconds. "
						+ "Consider increasing the timeout or setting exitAfterTraining=true.");
			}

			int exitCode = process.exitValue();
			if (exitCode != 0) {
				throw new MojoExecutionException("Training run failed with exit code: " + exitCode);
			}

			// Check that the output file was generated
			if (!Files.exists(outputFilePath)) {
				throw new MojoExecutionException("Training run completed but no output file was generated at: "
						+ outputFilePath + ". Ensure the autoconfiguration-optimizer-core is on the classpath.");
			}

			// Copy to target directory
			copyToTargetDirectory(outputFilePath);

			// Inject core classes and resources into the build output directory so the
			// optimizer works at runtime without requiring the core as a project
			// dependency
			injectCoreFiles();

			getLog().info("Spring Boot Autoconfiguration Optimizer: Training run complete. "
					+ "Properties file copied to: " + targetDirectory);

		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MojoExecutionException("Training run was interrupted", e);
		}
		catch (IOException e) {
			throw new MojoExecutionException("Failed to run training", e);
		}
	}

	private List<String> buildCommand(Path outputFilePath) throws MojoExecutionException {
		List<String> command = new ArrayList<>();

		// Java executable
		String javaHome = System.getProperty("java.home");
		String javaExec = Paths.get(javaHome, "bin", "java").toString();
		command.add(javaExec);

		// JVM arguments
		command.addAll(jvmArguments);

		// Training run system properties
		command.add("-Dautoconfiguration.optimizer.training-run=true");
		command.add("-Dautoconfiguration.optimizer.exit-after-training=true");
		command.add("-Dautoconfiguration.optimizer.output-directory=" + workingDirectory.getAbsolutePath());
		command.add("-Dautoconfiguration.optimizer.output-file=" + outputFile);

		if (jar != null && jar.exists()) {
			// Run via executable JAR
			command.add("-jar");
			command.add(jar.getAbsolutePath());
		}
		else {
			// Run via classpath and main class
			String classpath = buildClasspath();
			command.add("-cp");
			command.add(classpath);
			command.add(resolveMainClass());
		}

		return command;
	}

	private String buildClasspath() {
		List<String> classpathEntries = new ArrayList<>();

		// Add compiled classes
		classpathEntries.add(project.getBuild().getOutputDirectory());

		// Always include the optimizer core JAR so training works without the user
		// declaring it as a project dependency
		Path coreJar = CoreInjector.findCoreJar();
		if (coreJar != null) {
			classpathEntries.add(coreJar.toString());
		}

		// Add all compile and runtime dependencies
		for (Artifact artifact : project.getArtifacts()) {
			if (artifact.getFile() != null && (Artifact.SCOPE_COMPILE.equals(artifact.getScope())
					|| Artifact.SCOPE_RUNTIME.equals(artifact.getScope()))) {
				classpathEntries.add(artifact.getFile().getAbsolutePath());
			}
		}

		return String.join(File.pathSeparator, classpathEntries);
	}

	private String resolveMainClass() throws MojoExecutionException {
		if (mainClass != null && !mainClass.isBlank()) {
			return mainClass;
		}

		// Try to get from Spring Boot Maven plugin configuration
		String springBootMainClass = (String) project.getProperties().get("start-class");
		if (springBootMainClass != null) {
			return springBootMainClass;
		}

		// Try to auto-detect by scanning compiled classes for @SpringBootApplication
		// or @SpringBootConfiguration
		Path outputDirectory = Paths.get(project.getBuild().getOutputDirectory());
		try {
			Optional<String> detected = MainClassFinder.findMainClass(outputDirectory);
			if (detected.isPresent()) {
				getLog().info("Spring Boot Autoconfiguration Optimizer: Auto-detected main class: " + detected.get());
				return detected.get();
			}
		}
		catch (IOException e) {
			getLog().debug("Could not scan classes directory for main class: " + e.getMessage());
		}

		throw new MojoExecutionException("Could not determine main class. Please configure the 'mainClass' parameter, "
				+ "set 'start-class' property in your project, or ensure the project is compiled "
				+ "and contains a class annotated with @SpringBootApplication.");
	}

	private void copyToTargetDirectory(Path sourceFile) throws IOException {
		Path targetDir = targetDirectory.toPath();
		Files.createDirectories(targetDir);
		Path targetFile = targetDir.resolve(outputFile);
		Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
		getLog().info("Spring Boot Autoconfiguration Optimizer: Copied training file to: " + targetFile);
	}

	private void injectCoreFiles() throws MojoExecutionException {
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
