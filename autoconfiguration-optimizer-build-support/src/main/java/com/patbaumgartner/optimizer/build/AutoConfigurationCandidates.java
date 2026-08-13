package com.patbaumgartner.optimizer.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Reads the registered auto-configuration candidates from a classpath without starting an
 * application, so a build can compare the candidates available now against those recorded
 * during the training run.
 */
public final class AutoConfigurationCandidates {

	private static final String IMPORTS_LOCATION = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

	private AutoConfigurationCandidates() {
	}

	/**
	 * Collects the auto-configuration candidate class names declared by the given
	 * classpath entries, which may be directories or JAR files.
	 * @param classpathEntries the classpath entries to scan
	 * @return the candidate class names, sorted
	 * @throws IOException if an entry cannot be read
	 */
	public static Set<String> scan(Collection<Path> classpathEntries) throws IOException {
		Set<String> candidates = new TreeSet<>();
		for (Path entry : classpathEntries) {
			if (Files.isDirectory(entry)) {
				Path imports = entry.resolve(IMPORTS_LOCATION);
				if (Files.isRegularFile(imports)) {
					try (InputStream in = Files.newInputStream(imports)) {
						readInto(in, candidates);
					}
				}
			}
			else if (Files.isRegularFile(entry)) {
				readFromJar(entry, candidates);
			}
		}
		return candidates;
	}

	private static void readFromJar(Path jar, Set<String> candidates) throws IOException {
		try (JarFile jarFile = new JarFile(jar.toFile())) {
			JarEntry entry = jarFile.getJarEntry(IMPORTS_LOCATION);
			if (entry != null) {
				try (InputStream in = jarFile.getInputStream(entry)) {
					readInto(in, candidates);
				}
			}
		}
	}

	private static void readInto(InputStream in, Set<String> candidates) throws IOException {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				int comment = line.indexOf('#');
				String candidate = ((comment != -1) ? line.substring(0, comment) : line).trim();
				if (!candidate.isEmpty()) {
					candidates.add(candidate);
				}
			}
		}
	}

}
