package com.fortytwotalents.optimizer.gradle;

import com.fortytwotalents.optimizer.OptimizedAutoConfigurationImportFilter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Utility for locating the {@code autoconfiguration-optimizer-core} JAR on the plugin
 * classpath and injecting its classes and META-INF resources into a target directory
 * (typically the project's main classes output directory).
 */
final class CoreInjector {

	private static final String SPRING_FACTORIES = "META-INF/spring.factories";

	private static final String AUTOCONFIG_IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

	private CoreInjector() {
	}

	/**
	 * Locates the {@code autoconfiguration-optimizer-core} JAR on the plugin classpath.
	 * @return path to the core JAR, or {@code null} if running from a directory (e.g.,
	 * IDE/development mode)
	 */
	static Path findCoreJar() {
		URL location = OptimizedAutoConfigurationImportFilter.class.getProtectionDomain().getCodeSource()
			.getLocation();
		String locationStr = location.toString();
		if (locationStr.endsWith("/") || locationStr.endsWith("\\")) {
			return null;
		}
		try {
			return Path.of(location.toURI());
		}
		catch (URISyntaxException ex) {
			throw new IllegalStateException("Failed to locate autoconfiguration-optimizer-core JAR: " + location, ex);
		}
	}

	/**
	 * Extracts class files and selected META-INF resources from the core JAR into the
	 * given output directory, merging {@code spring.factories} and
	 * {@code AutoConfiguration.imports} files if they already exist.
	 * @param coreJar path to the optimizer core JAR
	 * @param outputDir target directory (e.g. {@code build/classes/java/main})
	 * @throws IOException if any I/O error occurs
	 */
	static void injectCoreJarContents(Path coreJar, Path outputDir) throws IOException {
		Files.createDirectories(outputDir);
		Path canonicalOutputDir = outputDir.toRealPath();
		try (JarFile jarFile = new JarFile(coreJar.toFile())) {
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				String name = entry.getName();

				if (shouldSkipEntry(name)) {
					continue;
				}

				// Guard against Zip Slip: reject any entry whose resolved path escapes
				// the target output directory.
				Path targetPath = outputDir.resolve(name).normalize();
				if (!targetPath.startsWith(canonicalOutputDir)) {
					throw new IOException(
							"Zip Slip: JAR entry '" + name + "' would be extracted outside the target directory '"
									+ canonicalOutputDir + "'. Aborting injection.");
				}

				if (name.equals(SPRING_FACTORIES)) {
					try (InputStream is = jarFile.getInputStream(entry)) {
						mergeSpringFactories(is, targetPath);
					}
				}
				else if (name.equals(AUTOCONFIG_IMPORTS)) {
					try (InputStream is = jarFile.getInputStream(entry)) {
						mergeLineBasedFile(is, targetPath);
					}
				}
				else if (!entry.isDirectory()) {
					if (!Files.exists(targetPath)) {
						Files.createDirectories(targetPath.getParent());
						try (InputStream is = jarFile.getInputStream(entry)) {
							Files.copy(is, targetPath);
						}
					}
				}
			}
		}
	}

	private static boolean shouldSkipEntry(String name) {
		if (name.equals("META-INF/MANIFEST.MF")) {
			return true;
		}
		if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) {
			return true;
		}
		return false;
	}

	private static void mergeSpringFactories(InputStream source, Path target) throws IOException {
		Properties coreProps = new Properties();
		coreProps.load(source);

		if (Files.exists(target)) {
			Properties existingProps = new Properties();
			try (InputStream existingStream = Files.newInputStream(target)) {
				existingProps.load(existingStream);
			}

			boolean changed = false;
			for (String key : coreProps.stringPropertyNames()) {
				String coreValues = coreProps.getProperty(key);
				String existingValue = existingProps.getProperty(key);
				if (existingValue == null || existingValue.isBlank()) {
					existingProps.setProperty(key, coreValues);
					changed = true;
				}
				else {
					Set<String> existing = parseCommaSeparated(existingValue);
					Set<String> toAdd = parseCommaSeparated(coreValues);
					toAdd.removeAll(existing);
					if (!toAdd.isEmpty()) {
						existing.addAll(toAdd);
						existingProps.setProperty(key, String.join(",\\\n  ", existing));
						changed = true;
					}
				}
			}

			if (changed) {
				try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
						StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
					existingProps.store(writer, null);
				}
			}
		}
		else {
			Files.createDirectories(target.getParent());
			Map<String, String> entries = new LinkedHashMap<>();
			for (String key : coreProps.stringPropertyNames()) {
				entries.put(key, coreProps.getProperty(key));
			}
			try (var writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
				for (Map.Entry<String, String> e : entries.entrySet()) {
					writer.write(e.getKey() + "=\\\n  " + e.getValue().replace(",", ",\\\n  "));
					writer.newLine();
				}
			}
		}
	}

	private static void mergeLineBasedFile(InputStream source, Path target) throws IOException {
		String coreContent = new String(source.readAllBytes(), StandardCharsets.UTF_8);
		Set<String> coreLines = new LinkedHashSet<>();
		for (String line : coreContent.split("\\r?\\n")) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
				coreLines.add(trimmed);
			}
		}

		if (Files.exists(target)) {
			String existing = Files.readString(target, StandardCharsets.UTF_8);
			Set<String> existingLines = new LinkedHashSet<>();
			for (String line : existing.split("\\r?\\n")) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					existingLines.add(trimmed);
				}
			}

			coreLines.removeAll(existingLines);
			if (!coreLines.isEmpty()) {
				String toAppend = "\n" + String.join("\n", coreLines) + "\n";
				Files.writeString(target, existing + toAppend, StandardCharsets.UTF_8, StandardOpenOption.WRITE);
			}
		}
		else {
			Files.createDirectories(target.getParent());
			Files.writeString(target, coreContent, StandardCharsets.UTF_8);
		}
	}

	private static Set<String> parseCommaSeparated(String value) {
		Set<String> result = new LinkedHashSet<>();
		for (String part : value.split(",")) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty()) {
				result.add(trimmed);
			}
		}
		return result;
	}

}
