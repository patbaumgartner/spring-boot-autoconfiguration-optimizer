package com.fortytwotalents.optimizer.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scans compiled class files to find a class annotated with
 * {@code @SpringBootApplication} or {@code @SpringBootConfiguration}, including
 * annotations that have {@code @SpringBootConfiguration} as a meta-annotation anywhere in
 * their annotation hierarchy.
 *
 * <p>
 * The scan is done by inspecting raw class file bytes for annotation descriptor
 * constants, avoiding the need to load classes into the JVM. Annotation types are
 * distinguished from regular classes by parsing the class file header to check for the
 * {@code ACC_ANNOTATION} access flag.
 */
class MainClassFinder {

	private static final String SPRING_BOOT_APPLICATION_DESC = "Lorg/springframework/boot/autoconfigure/SpringBootApplication;";

	private static final String SPRING_BOOT_CONFIGURATION_DESC = "Lorg/springframework/boot/SpringBootConfiguration;";

	private MainClassFinder() {
	}

	/**
	 * Finds the main class by scanning compiled {@code .class} files in the given
	 * directory for {@code @SpringBootApplication} or {@code @SpringBootConfiguration},
	 * including custom annotations that have {@code @SpringBootConfiguration} as a
	 * meta-annotation.
	 * @param classesDirectory the directory containing compiled class files
	 * @return the fully-qualified class name, or empty if not found
	 * @throws IOException if an I/O error occurs while walking the directory
	 */
	static Optional<String> findMainClass(Path classesDirectory) throws IOException {
		if (!Files.isDirectory(classesDirectory)) {
			return Optional.empty();
		}

		List<Path> classFiles;
		try (Stream<Path> stream = Files.walk(classesDirectory)) {
			classFiles = stream.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList());
		}

		Set<String> springBootAnnotations = new LinkedHashSet<>();
		springBootAnnotations.add(SPRING_BOOT_APPLICATION_DESC);
		springBootAnnotations.add(SPRING_BOOT_CONFIGURATION_DESC);

		// Iteratively discover custom annotation types that have @SpringBootConfiguration
		// in their meta-annotation hierarchy and add their descriptors to the search set.
		boolean changed;
		do {
			changed = false;
			for (Path classFile : classFiles) {
				try {
					byte[] bytes = Files.readAllBytes(classFile);
					if (isAnnotationType(bytes) && hasAnyAnnotation(bytes, springBootAnnotations)) {
						String descriptor = toDescriptor(classesDirectory, classFile);
						if (springBootAnnotations.add(descriptor)) {
							changed = true;
						}
					}
				}
				catch (IOException e) {
					// ignore unreadable files
				}
			}
		}
		while (changed);

		// Find the first non-annotation class that carries one of the known Spring Boot
		// annotation descriptors.
		for (Path classFile : classFiles) {
			try {
				byte[] bytes = Files.readAllBytes(classFile);
				if (!isAnnotationType(bytes) && hasAnyAnnotation(bytes, springBootAnnotations)) {
					return Optional.of(toClassName(classesDirectory, classFile));
				}
			}
			catch (IOException e) {
				// ignore unreadable files
			}
		}

		return Optional.empty();
	}

	private static boolean hasAnyAnnotation(byte[] classBytes, Set<String> descriptors) {
		for (String descriptor : descriptors) {
			if (containsBytes(classBytes, descriptor.getBytes(StandardCharsets.UTF_8))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns {@code true} if the class file has the {@code ACC_ANNOTATION} (0x2000)
	 * access flag set, indicating that it declares a Java annotation type. The constant
	 * pool is parsed according to the JVM specification to locate the
	 * {@code access_flags} field that immediately follows it.
	 */
	private static boolean isAnnotationType(byte[] classBytes) {
		if (classBytes.length < 10) {
			return false;
		}
		try {
			int pos = skipConstantPool(classBytes);
			if (pos < 0 || pos + 2 > classBytes.length) {
				return false;
			}
			int accessFlags = readU2(classBytes, pos);
			return (accessFlags & 0x2000) != 0;
		}
		catch (ArrayIndexOutOfBoundsException ex) {
			return false;
		}
	}

	/**
	 * Skips over the constant pool in {@code classBytes} and returns the byte offset of
	 * the {@code access_flags} field that follows it. Returns {@code -1} if the bytes
	 * cannot be parsed.
	 */
	private static int skipConstantPool(byte[] classBytes) {
		int cpCount = readU2(classBytes, 8);
		int pos = 10;
		for (int i = 1; i < cpCount; i++) {
			if (pos >= classBytes.length) {
				return -1;
			}
			int tag = classBytes[pos++] & 0xFF;
			switch (tag) {
				case 1: // CONSTANT_Utf8
					int length = readU2(classBytes, pos);
					pos += 2 + length;
					break;
				case 3: // CONSTANT_Integer
				case 4: // CONSTANT_Float
					pos += 4;
					break;
				case 5: // CONSTANT_Long
				case 6: // CONSTANT_Double
					pos += 8;
					i++; // these tags occupy two constant pool slots
					break;
				case 7: // CONSTANT_Class
				case 8: // CONSTANT_String
				case 16: // CONSTANT_MethodType
				case 19: // CONSTANT_Module
				case 20: // CONSTANT_Package
					pos += 2;
					break;
				case 9: // CONSTANT_Fieldref
				case 10: // CONSTANT_Methodref
				case 11: // CONSTANT_InterfaceMethodref
				case 12: // CONSTANT_NameAndType
				case 17: // CONSTANT_Dynamic
				case 18: // CONSTANT_InvokeDynamic
					pos += 4;
					break;
				case 15: // CONSTANT_MethodHandle
					pos += 3;
					break;
				default:
					return -1; // unknown tag; cannot parse further
			}
		}
		return pos;
	}

	private static int readU2(byte[] bytes, int pos) {
		return ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
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

	private static String toDescriptor(Path baseDir, Path classFile) {
		String relativePath = baseDir.relativize(classFile).toString().replace('\\', '/');
		return "L" + relativePath.substring(0, relativePath.length() - ".class".length()) + ";";
	}

	private static String toClassName(Path baseDir, Path classFile) {
		String relativePath = baseDir.relativize(classFile).toString();
		String dotSeparated = relativePath.replace('/', '.').replace('\\', '.');
		return dotSeparated.substring(0, dotSeparated.length() - ".class".length());
	}

}
