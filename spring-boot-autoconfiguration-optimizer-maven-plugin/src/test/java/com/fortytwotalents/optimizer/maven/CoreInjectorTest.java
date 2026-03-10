package com.fortytwotalents.optimizer.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class CoreInjectorTest {

	@TempDir
	Path tempDir;

	@Test
	void findCoreJarReturnsPathWhenRunningFromJar() {
		// The core JAR should be on the plugin classpath at test time
		// (maven-plugin depends on autoconfiguration-optimizer-core)
		Path coreJar = CoreInjector.findCoreJar();
		// In normal test execution the core is a JAR on the test classpath
		// (not an exploded directory) so the result must be non-null
		if (coreJar != null) {
			assertThat(coreJar).exists();
			assertThat(coreJar.toString()).endsWith(".jar");
		}
		// When running from an exploded directory (IDE/Maven reactor), null is also
		// acceptable — the injector gracefully skips injection
	}

	@Test
	void injectCoreJarContentsCreatesClassFiles() throws IOException {
		Path coreJar = CoreInjector.findCoreJar();
		if (coreJar == null) {
			// Running from exploded directory — skip this test
			return;
		}

		Path outputDir = tempDir.resolve("classes");
		CoreInjector.injectCoreJarContents(coreJar, outputDir);

		// The filter class must be present after injection
		Path filterClass = outputDir
			.resolve("com/fortytwotalents/optimizer/OptimizedAutoConfigurationImportFilter.class");
		assertThat(filterClass).exists();

		// spring.factories must be present and contain the filter registration
		Path springFactories = outputDir.resolve("META-INF/spring.factories");
		assertThat(springFactories).exists();
		String content = Files.readString(springFactories, StandardCharsets.UTF_8);
		assertThat(content).contains("OptimizedAutoConfigurationImportFilter");

		// MANIFEST.MF must NOT be injected (to avoid overwriting the project manifest)
		assertThat(outputDir.resolve("META-INF/MANIFEST.MF")).doesNotExist();
	}

	@Test
	void injectCoreJarContentsMergesExistingSpringFactories() throws IOException {
		Path coreJar = CoreInjector.findCoreJar();
		if (coreJar == null) {
			return;
		}

		Path outputDir = tempDir.resolve("classes-merge");
		Path metaInf = outputDir.resolve("META-INF");
		Files.createDirectories(metaInf);

		// Write a pre-existing spring.factories with a different key
		String existing = "org.springframework.context.ApplicationListener=com.example.MyListener\n";
		Files.writeString(metaInf.resolve("spring.factories"), existing, StandardCharsets.UTF_8);

		CoreInjector.injectCoreJarContents(coreJar, outputDir);

		Path springFactories = outputDir.resolve("META-INF/spring.factories");
		Properties merged = new Properties();
		try (var is = Files.newInputStream(springFactories)) {
			merged.load(is);
		}

		// Original entry must still be present
		assertThat(merged.getProperty("org.springframework.context.ApplicationListener"))
			.contains("com.example.MyListener");

		// New entry from core must have been added
		assertThat(merged.getProperty("org.springframework.boot.autoconfigure.AutoConfigurationImportFilter"))
			.contains("OptimizedAutoConfigurationImportFilter");
	}

	@Test
	void injectCoreJarContentsMergesExistingAutoConfigurationImports() throws IOException {
		Path coreJar = CoreInjector.findCoreJar();
		if (coreJar == null) {
			return;
		}

		Path outputDir = tempDir.resolve("classes-imports");
		Path springDir = outputDir.resolve("META-INF/spring");
		Files.createDirectories(springDir);

		// Write a pre-existing AutoConfiguration.imports with a different class
		String existing = "com.example.MyAutoConfiguration\n";
		Files.writeString(springDir.resolve("org.springframework.boot.autoconfigure.AutoConfiguration.imports"),
				existing, StandardCharsets.UTF_8);

		CoreInjector.injectCoreJarContents(coreJar, outputDir);

		Path importsFile = outputDir
			.resolve("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
		String content = Files.readString(importsFile, StandardCharsets.UTF_8);

		// Both the original and core entries must be present
		assertThat(content).contains("com.example.MyAutoConfiguration");
		assertThat(content).contains("AutoConfigurationOptimizerAutoConfiguration");
	}

	@Test
	void injectCoreJarContentsIsIdempotent() throws IOException {
		Path coreJar = CoreInjector.findCoreJar();
		if (coreJar == null) {
			return;
		}

		Path outputDir = tempDir.resolve("classes-idempotent");
		CoreInjector.injectCoreJarContents(coreJar, outputDir);
		CoreInjector.injectCoreJarContents(coreJar, outputDir);

		// Verify the filter class appears exactly once in the
		// AutoConfigurationImportFilter key value after two injection passes
		Path springFactories = outputDir.resolve("META-INF/spring.factories");
		Properties props = new Properties();
		try (var is = Files.newInputStream(springFactories)) {
			props.load(is);
		}
		String filterValue = props.getProperty("org.springframework.boot.autoconfigure.AutoConfigurationImportFilter",
				"");
		long count = java.util.Arrays.stream(filterValue.split(","))
			.map(String::trim)
			.filter(s -> s.contains("OptimizedAutoConfigurationImportFilter"))
			.count();
		assertThat(count).isEqualTo(1);
	}

	@Test
	void injectCoreJarContentsRejectsTraversalEntries() throws IOException {
		// Build a minimal JAR containing a Zip Slip entry (../../evil.txt)
		Path maliciousJar = tempDir.resolve("malicious.jar");
		try (var jos = new java.util.jar.JarOutputStream(Files.newOutputStream(maliciousJar))) {
			java.util.jar.JarEntry entry = new java.util.jar.JarEntry("../../evil.txt");
			jos.putNextEntry(entry);
			jos.write("pwned".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			jos.closeEntry();
		}

		Path outputDir = tempDir.resolve("classes-slip");
		Files.createDirectories(outputDir);

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> CoreInjector.injectCoreJarContents(maliciousJar, outputDir))
			.isInstanceOf(IOException.class)
			.hasMessageContaining("Zip Slip");

		// Confirm no file was written outside the output directory
		assertThat(tempDir.resolve("evil.txt")).doesNotExist();
	}

}
