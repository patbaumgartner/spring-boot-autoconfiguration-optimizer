package com.fortytwotalents.optimizer.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Injects the {@code autoconfiguration-optimizer-core} classes and META-INF resources
 * into the project's compile output directory ({@code target/classes}) before packaging.
 *
 * <p>
 * This goal should be configured to run in the {@code prepare-package} phase so that the
 * optimizer core classes are present in the build output whenever the project is packaged.
 * The core is injected unconditionally so that the resulting JAR is always capable of
 * performing a training run (even when no training file exists yet). When no training file
 * is present at runtime the {@code OptimizedAutoConfigurationImportFilter} simply passes
 * all auto-configurations through, so there is no behavioral change for unoptimized
 * applications.
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
