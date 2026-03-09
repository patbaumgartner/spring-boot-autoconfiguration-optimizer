package com.fortytwotalents.optimizer.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Scans compiled class files to find a class annotated with
 * {@code @SpringBootApplication} or {@code @SpringBootConfiguration}.
 *
 * <p>
 * The scan is done by inspecting raw class file bytes for annotation descriptor constants,
 * avoiding the need to load classes into the JVM.
 */
class MainClassFinder {

	private static final byte[] SPRING_BOOT_APPLICATION = "Lorg/springframework/boot/autoconfigure/SpringBootApplication;"
		.getBytes(StandardCharsets.UTF_8);

	private static final byte[] SPRING_BOOT_CONFIGURATION = "Lorg/springframework/boot/SpringBootConfiguration;"
		.getBytes(StandardCharsets.UTF_8);

	private MainClassFinder() {
	}

	/**
	 * Finds the main class by scanning compiled {@code .class} files in the given
	 * directory for {@code @SpringBootApplication} or {@code @SpringBootConfiguration}.
	 * @param classesDirectory the directory containing compiled class files
	 * @return the fully-qualified class name, or empty if not found
	 * @throws IOException if an I/O error occurs while walking the directory
	 */
	static Optional<String> findMainClass(Path classesDirectory) throws IOException {
		if (!Files.isDirectory(classesDirectory)) {
			return Optional.empty();
		}
		try (Stream<Path> stream = Files.walk(classesDirectory)) {
			return stream.filter(p -> p.toString().endsWith(".class"))
				.filter(MainClassFinder::hasSpringBootAnnotation)
				.findFirst()
				.map(p -> toClassName(classesDirectory, p));
		}
	}

	private static boolean hasSpringBootAnnotation(Path classFile) {
		try {
			byte[] bytes = Files.readAllBytes(classFile);
			return containsBytes(bytes, SPRING_BOOT_APPLICATION) || containsBytes(bytes, SPRING_BOOT_CONFIGURATION);
		}
		catch (IOException e) {
			return false;
		}
	}

	private static boolean containsBytes(byte[] data, byte[] pattern) {
		outer: for (int i = 0; i <= data.length - pattern.length; i++) {
			for (int j = 0; j < pattern.length; j++) {
				if (data[i + j] != pattern[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	private static String toClassName(Path baseDir, Path classFile) {
		String relativePath = baseDir.relativize(classFile).toString();
		String dotSeparated = relativePath.replace('/', '.').replace('\\', '.');
		return dotSeparated.substring(0, dotSeparated.length() - ".class".length());
	}

}
