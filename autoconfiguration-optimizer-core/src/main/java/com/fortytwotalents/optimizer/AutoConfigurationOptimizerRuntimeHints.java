package com.fortytwotalents.optimizer;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * {@link RuntimeHintsRegistrar} for the Spring Boot Autoconfiguration Optimizer.
 *
 * <p>
 * Registers hints required for GraalVM native image compilation:
 * <ul>
 * <li>The optimizer properties file as a classpath resource</li>
 * <li>Reflection hints for {@link OptimizedAutoConfigurationImportFilter}</li>
 * </ul>
 */
public class AutoConfigurationOptimizerRuntimeHints implements RuntimeHintsRegistrar {

	@Override
	public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
		// Register the optimizer properties file so it's included in the native image
		hints.resources().registerPattern(OptimizedAutoConfigurationImportFilter.OPTIMIZER_PROPERTIES_FILE);

		// Register the AutoConfiguration.imports files so they're readable at runtime
		hints.resources()
			.registerPattern("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");

		// Register reflection for the import filter so it can be instantiated
		hints.reflection()
			.registerType(OptimizedAutoConfigurationImportFilter.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
					MemberCategory.INVOKE_PUBLIC_METHODS);

		// Register reflection for the TrainingRunApplicationListener
		hints.reflection()
			.registerType(TrainingRunApplicationListener.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
					MemberCategory.INVOKE_PUBLIC_METHODS);
	}

}
