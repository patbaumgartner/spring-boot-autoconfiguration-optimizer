package com.fortytwotalents.optimizer.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Gradle task that injects the {@code autoconfiguration-optimizer-core} classes and
 * META-INF resources into the project's main output directory before the {@code jar}
 * task runs.
 *
 * <p>
 * This task is automatically wired to run before the {@code jar} task when the
 * {@link AutoConfigurationOptimizerPlugin} is applied. The core is injected
 * unconditionally so that the resulting JAR is always capable of performing a training
 * run (even when no training file exists yet). When no training file is present at
 * runtime the {@code OptimizedAutoConfigurationImportFilter} simply passes all
 * auto-configurations through, so there is no behavioral change for unoptimized
 * applications.
 *
 * <p>
 * Together with the {@link TrainTask}, this removes the requirement to declare
 * {@code autoconfiguration-optimizer-core} as an explicit project dependency.
 */
@DisableCachingByDefault(because = "Injects files into the build output directory; result depends on the core JAR on the plugin classpath")
public abstract class InjectTask extends DefaultTask {

	/**
	 * The directory into which the core classes and resources are injected (typically the
	 * main classes output directory, e.g. {@code build/classes/java/main}).
	 */
	@OutputDirectory
	public abstract DirectoryProperty getOutputDirectory();

	@TaskAction
	public void inject() {
		Path coreJar = CoreInjector.findCoreJar();
		if (coreJar == null) {
			getLogger().debug(
					"Spring Boot Autoconfiguration Optimizer: Core JAR not found as a file. Skipping core injection.");
			return;
		}

		File outputDir = getOutputDirectory().get().getAsFile();
		try {
			CoreInjector.injectCoreJarContents(coreJar, outputDir.toPath());
			getLogger().lifecycle(
					"Spring Boot Autoconfiguration Optimizer: Core classes injected into: {}", outputDir);
		}
		catch (IOException ex) {
			throw new GradleException("Failed to inject optimizer core classes into build output", ex);
		}
	}

}
