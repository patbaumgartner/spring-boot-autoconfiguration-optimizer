package com.fortytwotalents.optimizer.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MainClassFinderTest {

	@TempDir
	Path tempDir;

	@Test
	void findsClassAnnotatedWithSpringBootApplication() throws IOException {
		Path classDir = createClassFile("com/example/MyApplication.class",
				"Lorg/springframework/boot/autoconfigure/SpringBootApplication;");

		Optional<String> result = MainClassFinder.findMainClass(classDir);

		assertThat(result).isPresent().contains("com.example.MyApplication");
	}

	@Test
	void findsClassAnnotatedWithSpringBootConfiguration() throws IOException {
		Path classDir = createClassFile("com/example/MyApp.class",
				"Lorg/springframework/boot/SpringBootConfiguration;");

		Optional<String> result = MainClassFinder.findMainClass(classDir);

		assertThat(result).isPresent().contains("com.example.MyApp");
	}

	@Test
	void findsClassAnnotatedWithCustomMetaAnnotation() throws IOException {
		// Custom annotation that is itself meta-annotated with @SpringBootConfiguration
		Path classDir = tempDir.resolve("classes-meta");
		createAnnotationClassFileInDir(classDir, "com/example/MyBootApp.class",
				"Lorg/springframework/boot/SpringBootConfiguration;");

		// Application class annotated only with the custom @MyBootApp annotation
		createClassFileInDir(classDir, "com/example/Application.class", "Lcom/example/MyBootApp;");

		Optional<String> result = MainClassFinder.findMainClass(classDir);

		assertThat(result).isPresent().contains("com.example.Application");
	}

	@Test
	void doesNotReturnAnnotationClassAsMainClass() throws IOException {
		// Only the annotation class is present; there is no application class annotated
		// with it, so no main class should be found.
		Path classDir = tempDir.resolve("classes-ann-only");
		createAnnotationClassFileInDir(classDir, "com/example/MyBootApp.class",
				"Lorg/springframework/boot/SpringBootConfiguration;");

		Optional<String> result = MainClassFinder.findMainClass(classDir);

		assertThat(result).isEmpty();
	}

	@Test
	void returnsEmptyWhenNoAnnotatedClassFound() throws IOException {
		Path classDir = tempDir.resolve("classes");
		Files.createDirectories(classDir.resolve("com/example"));
		Files.write(classDir.resolve("com/example/Other.class"),
				new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE });

		Optional<String> result = MainClassFinder.findMainClass(classDir);

		assertThat(result).isEmpty();
	}

	@Test
	void returnsEmptyForNonExistentDirectory() throws IOException {
		Optional<String> result = MainClassFinder.findMainClass(tempDir.resolve("nonexistent"));

		assertThat(result).isEmpty();
	}

	/**
	 * Creates a synthetic {@code .class} file containing the given annotation descriptor
	 * bytes under {@code classDir}. The file is intentionally not a valid class file so
	 * that {@code isAnnotationType} returns {@code false}, ensuring it is treated as a
	 * regular (non-annotation) class.
	 */
	private Path createClassFile(String relativePath, String annotationDescriptor) throws IOException {
		Path classDir = tempDir.resolve("classes-" + relativePath.hashCode());
		createClassFileInDir(classDir, relativePath, annotationDescriptor);
		return classDir;
	}

	private void createClassFileInDir(Path classDir, String relativePath, String annotationDescriptor)
			throws IOException {
		Path classFile = classDir.resolve(relativePath);
		Files.createDirectories(classFile.getParent());

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE });
		baos.write(new byte[] { 0, 0, 0, 61 });
		baos.write(annotationDescriptor.getBytes(StandardCharsets.UTF_8));

		Files.write(classFile, baos.toByteArray());
	}

	/**
	 * Creates a minimal but structurally valid annotation {@code .class} file under
	 * {@code classDir}. The file has the {@code ACC_ANNOTATION} flag set in its access
	 * flags so that {@code isAnnotationType} returns {@code true}. A
	 * {@code RuntimeVisibleAnnotations} attribute referencing
	 * {@code metaAnnotationDescriptor} is included so that the meta-annotation byte scan
	 * finds it.
	 */
	private void createAnnotationClassFileInDir(Path classDir, String relativePath, String metaAnnotationDescriptor)
			throws IOException {
		Path classFile = classDir.resolve(relativePath);
		Files.createDirectories(classFile.getParent());

		String internalName = relativePath.replace('\\', '/').substring(0, relativePath.length() - ".class".length());
		byte[] internalNameBytes = internalName.getBytes(StandardCharsets.UTF_8);
		byte[] objectBytes = "java/lang/Object".getBytes(StandardCharsets.UTF_8);
		byte[] annotationInterfaceBytes = "java/lang/annotation/Annotation".getBytes(StandardCharsets.UTF_8);
		byte[] metaAnnotationBytes = metaAnnotationDescriptor.getBytes(StandardCharsets.UTF_8);
		byte[] rvaBytes = "RuntimeVisibleAnnotations".getBytes(StandardCharsets.UTF_8);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		dos.writeInt(0xCAFEBABE); // magic
		dos.writeShort(0); // minor version
		dos.writeShort(61); // major version (Java 17)
		dos.writeShort(9); // constant_pool_count (8 entries + 1)

		// CP #1: Utf8 <internalName>
		dos.writeByte(1);
		dos.writeShort(internalNameBytes.length);
		dos.write(internalNameBytes);

		// CP #2: Class -> #1
		dos.writeByte(7);
		dos.writeShort(1);

		// CP #3: Utf8 "java/lang/Object"
		dos.writeByte(1);
		dos.writeShort(objectBytes.length);
		dos.write(objectBytes);

		// CP #4: Class -> #3
		dos.writeByte(7);
		dos.writeShort(3);

		// CP #5: Utf8 "java/lang/annotation/Annotation"
		dos.writeByte(1);
		dos.writeShort(annotationInterfaceBytes.length);
		dos.write(annotationInterfaceBytes);

		// CP #6: Class -> #5
		dos.writeByte(7);
		dos.writeShort(5);

		// CP #7: Utf8 <metaAnnotationDescriptor>
		dos.writeByte(1);
		dos.writeShort(metaAnnotationBytes.length);
		dos.write(metaAnnotationBytes);

		// CP #8: Utf8 "RuntimeVisibleAnnotations"
		dos.writeByte(1);
		dos.writeShort(rvaBytes.length);
		dos.write(rvaBytes);

		// access_flags: ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT | ACC_ANNOTATION
		dos.writeShort(0x2601);
		dos.writeShort(2); // this_class -> #2
		dos.writeShort(4); // super_class -> #4
		dos.writeShort(1); // interfaces_count
		dos.writeShort(6); // interfaces[0] -> #6 (java/lang/annotation/Annotation)
		dos.writeShort(0); // fields_count
		dos.writeShort(0); // methods_count
		dos.writeShort(1); // attributes_count

		// RuntimeVisibleAnnotations attribute
		dos.writeShort(8); // attribute_name_index -> #8
		dos.writeInt(6); // attribute_length: num_annotations(2) + type_index(2) +
							// pairs(2)
		dos.writeShort(1); // num_annotations
		dos.writeShort(7); // annotation.type_index -> #7 (metaAnnotationDescriptor)
		dos.writeShort(0); // annotation.num_element_value_pairs

		dos.flush();
		Files.write(classFile, baos.toByteArray());
	}

}
