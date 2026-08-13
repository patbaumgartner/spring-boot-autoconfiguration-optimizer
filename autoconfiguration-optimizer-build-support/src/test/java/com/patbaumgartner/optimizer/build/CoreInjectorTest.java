package com.patbaumgartner.optimizer.build;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CoreInjectorTest {

	private static final String FACTORIES = "META-INF/spring.factories";

	private static final String IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

	private static final String FILTER_KEY = "org.springframework.boot.autoconfigure.AutoConfigurationImportFilter";

	private static final String FILTER_CLASS = "com.patbaumgartner.optimizer.OptimizedAutoConfigurationImportFilter";

	private static final String FILTER_ENTRY = "com/patbaumgartner/optimizer/OptimizedAutoConfigurationImportFilter.class";

	@TempDir
	Path tempDir;

	@Test
	void findCoreJarReturnsAJarOrNullWhenRunningFromAnExplodedDirectory() {
		Path coreJar = CoreInjector.findCoreJar();

		if (coreJar != null) {
			assertThat(coreJar).exists();
			assertThat(coreJar.toString()).endsWith(".jar");
		}
	}

	@Test
	void injectsClassesAndSpringMetadata() throws Exception {
		Path outputDir = tempDir.resolve("classes");

		CoreInjector.injectCoreJarContents(coreJar(), outputDir);

		assertThat(outputDir.resolve(FILTER_ENTRY)).exists();
		assertThat(factoryValues(outputDir).get(FILTER_KEY)).containsExactly(FILTER_CLASS);
		assertThat(outputDir.resolve(IMPORTS)).content(StandardCharsets.UTF_8)
			.contains("com.patbaumgartner.optimizer.AutoConfigurationOptimizerAutoConfiguration");
	}

	@Test
	void doesNotInjectTheCoreManifest() throws Exception {
		Path outputDir = tempDir.resolve("classes-manifest");

		CoreInjector.injectCoreJarContents(coreJar(), outputDir);

		assertThat(outputDir.resolve("META-INF/MANIFEST.MF")).doesNotExist();
	}

	@Test
	void doesNotInjectTheCoreBuildProvenance() throws Exception {
		Path outputDir = tempDir.resolve("classes-provenance");

		CoreInjector.injectCoreJarContents(coreJar(), outputDir);

		assertThat(outputDir.resolve("META-INF/maven")).doesNotExist();
	}

	@Test
	void mergesIntoExistingSpringFactoriesWithADifferentKey() throws Exception {
		Path outputDir = tempDir.resolve("classes-merge");
		writeFile(outputDir.resolve(FACTORIES),
				"org.springframework.context.ApplicationListener=com.example.MyListener\n");

		CoreInjector.injectCoreJarContents(coreJar(), outputDir);

		Map<String, String[]> values = factoryValues(outputDir);
		assertThat(values.get("org.springframework.context.ApplicationListener"))
			.containsExactly("com.example.MyListener");
		assertThat(values.get(FILTER_KEY)).containsExactly(FILTER_CLASS);
	}

	/**
	 * Regression test: merging into a key the application already declares used to route
	 * the combined value through {@link Properties#store}, which escaped the embedded
	 * line-continuation backslash. Reading the result back yielded a "class name"
	 * beginning with a literal backslash that Spring Boot could not instantiate.
	 */
	@Test
	void mergesIntoExistingSpringFactoriesWithTheSameKeyWithoutCorruptingValues() throws Exception {
		Path outputDir = tempDir.resolve("classes-merge-same-key");
		writeFile(outputDir.resolve(FACTORIES), FILTER_KEY + "=com.example.ExistingFilter\n");

		CoreInjector.injectCoreJarContents(coreJar(), outputDir);

		assertThat(factoryValues(outputDir).get(FILTER_KEY)).containsExactlyInAnyOrder("com.example.ExistingFilter",
				FILTER_CLASS);
	}

	@Test
	void isIdempotent() throws Exception {
		Path outputDir = tempDir.resolve("classes-idempotent");

		CoreInjector.injectCoreJarContents(coreJar(), outputDir);
		CoreInjector.injectCoreJarContents(coreJar(), outputDir);

		assertThat(factoryValues(outputDir).get(FILTER_KEY)).containsExactly(FILTER_CLASS);
		long importLines = Files.readString(outputDir.resolve(IMPORTS), StandardCharsets.UTF_8)
			.lines()
			.filter((line) -> line.contains("AutoConfigurationOptimizerAutoConfiguration"))
			.count();
		assertThat(importLines).isEqualTo(1);
	}

	@Test
	void mergesIntoExistingAutoConfigurationImports() throws Exception {
		Path outputDir = tempDir.resolve("classes-imports");
		writeFile(outputDir.resolve(IMPORTS), "com.example.MyAutoConfiguration\n");

		CoreInjector.injectCoreJarContents(coreJar(), outputDir);

		assertThat(outputDir.resolve(IMPORTS)).content(StandardCharsets.UTF_8)
			.contains("com.example.MyAutoConfiguration")
			.contains("com.patbaumgartner.optimizer.AutoConfigurationOptimizerAutoConfiguration");
	}

	/**
	 * Regression test: injected classes are owned by the plugin, so upgrading it must
	 * replace them. Skipping files that already existed left the build output holding
	 * classes from a previously injected core version.
	 */
	@Test
	void replacesPreviouslyInjectedCoreClasses() throws Exception {
		Path outputDir = tempDir.resolve("classes-stale");
		Path filterClass = outputDir.resolve(FILTER_ENTRY);
		writeFile(filterClass, "stale bytes from an older core");

		CoreInjector.injectCoreJarContents(coreJar(), outputDir);

		assertThat(filterClass).content(StandardCharsets.UTF_8).isEqualTo("filter class bytes");
	}

	@Test
	void doesNotClobberApplicationOwnedResources() throws Exception {
		Path outputDir = tempDir.resolve("classes-app-owned");
		Path metadata = outputDir.resolve("META-INF/spring-configuration-metadata.json");
		writeFile(metadata, "{\"groups\":[\"application-owned\"]}");

		CoreInjector.injectCoreJarContents(coreJar(), outputDir);

		assertThat(metadata).content(StandardCharsets.UTF_8).isEqualTo("{\"groups\":[\"application-owned\"]}");
	}

	@Test
	void rejectsTraversalEntries() throws Exception {
		Path maliciousJar = tempDir.resolve("malicious.jar");
		writeJar(maliciousJar, Map.of("../../evil.txt", "pwned"));
		Path outputDir = tempDir.resolve("classes-slip");
		Files.createDirectories(outputDir);

		Assertions.assertThatThrownBy(() -> CoreInjector.injectCoreJarContents(maliciousJar, outputDir))
			.isInstanceOf(IOException.class)
			.hasMessageContaining("Zip Slip");

		assertThat(tempDir.resolve("evil.txt")).doesNotExist();
	}

	/**
	 * Builds a JAR with the same entry layout as the real
	 * {@code autoconfiguration-optimizer-core} artifact. Asserting against it keeps these
	 * tests deterministic instead of depending on whether the reactor happens to expose
	 * the core as a JAR or as an exploded directory.
	 */
	private Path coreJar() throws IOException {
		Path jar = tempDir.resolve("core.jar");
		if (Files.exists(jar)) {
			return jar;
		}
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
		entries.put(FILTER_ENTRY, "filter class bytes");
		entries.put("com/patbaumgartner/optimizer/AutoConfigurationOptimizerAutoConfiguration.class",
				"auto-configuration class bytes");
		entries.put("META-INF/spring-configuration-metadata.json", "{\"groups\":[\"core-owned\"]}");
		entries.put(IMPORTS, "com.patbaumgartner.optimizer.AutoConfigurationOptimizerAutoConfiguration\n");
		entries.put(FACTORIES, FILTER_KEY + "=\\\n  " + FILTER_CLASS + "\n");
		entries.put("META-INF/maven/com.patbaumgartner/autoconfiguration-optimizer-core/pom.properties",
				"artifactId=autoconfiguration-optimizer-core\n");
		writeJar(jar, entries);
		return jar;
	}

	private static void writeJar(Path jar, Map<String, String> entries) throws IOException {
		try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
			for (Map.Entry<String, String> entry : entries.entrySet()) {
				jos.putNextEntry(new JarEntry(entry.getKey()));
				jos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				jos.closeEntry();
			}
		}
	}

	private static void writeFile(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	/**
	 * Reads the merged {@code spring.factories} the way Spring Boot does, so a value that
	 * only looks correct in the raw file cannot pass.
	 */
	private static Map<String, String[]> factoryValues(Path outputDir) throws IOException {
		Properties properties = new Properties();
		try (var in = Files.newInputStream(outputDir.resolve(FACTORIES))) {
			properties.load(in);
		}
		Map<String, String[]> result = new LinkedHashMap<>();
		for (String key : properties.stringPropertyNames()) {
			result.put(key,
					Arrays.stream(properties.getProperty(key).split(",")).map(String::trim).toArray(String[]::new));
		}
		return result;
	}

}
