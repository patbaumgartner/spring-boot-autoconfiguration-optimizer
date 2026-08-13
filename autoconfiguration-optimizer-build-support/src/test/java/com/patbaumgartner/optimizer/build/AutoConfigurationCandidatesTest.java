package com.patbaumgartner.optimizer.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class AutoConfigurationCandidatesTest {

	private static final String IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

	@TempDir
	Path tempDir;

	@Test
	void readsCandidatesFromADirectory() throws Exception {
		Path classes = directoryWithImports("classes", "com.example.AAutoConfiguration\ncom.example.BAutoConfiguration\n");

		assertThat(AutoConfigurationCandidates.scan(List.of(classes)))
			.containsExactly("com.example.AAutoConfiguration", "com.example.BAutoConfiguration");
	}

	@Test
	void readsCandidatesFromAJar() throws Exception {
		Path jar = jarWithImports("library.jar", "com.example.LibraryAutoConfiguration\n");

		assertThat(AutoConfigurationCandidates.scan(List.of(jar)))
			.containsExactly("com.example.LibraryAutoConfiguration");
	}

	@Test
	void mergesCandidatesAcrossEntriesAndSortsThem() throws Exception {
		Path classes = directoryWithImports("classes", "com.example.ZAutoConfiguration\n");
		Path jar = jarWithImports("library.jar", "com.example.AAutoConfiguration\n");

		assertThat(AutoConfigurationCandidates.scan(List.of(classes, jar)))
			.containsExactly("com.example.AAutoConfiguration", "com.example.ZAutoConfiguration");
	}

	@Test
	void ignoresCommentsAndBlankLines() throws Exception {
		Path classes = directoryWithImports("classes",
				"# a comment\n\ncom.example.AAutoConfiguration # trailing\n   \n");

		assertThat(AutoConfigurationCandidates.scan(List.of(classes)))
			.containsExactly("com.example.AAutoConfiguration");
	}

	@Test
	void ignoresEntriesWithoutAnImportsFile() throws Exception {
		Path empty = Files.createDirectories(this.tempDir.resolve("empty"));
		Path jar = this.tempDir.resolve("no-imports.jar");
		try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
			jos.putNextEntry(new JarEntry("com/example/Thing.class"));
			jos.closeEntry();
		}

		assertThat(AutoConfigurationCandidates.scan(List.of(empty, jar, this.tempDir.resolve("missing")))).isEmpty();
	}

	@Test
	void failsOnAnUnreadableArchive() throws Exception {
		Path notAJar = this.tempDir.resolve("broken.jar");
		Files.writeString(notAJar, "this is not an archive");

		assertThatIOException().isThrownBy(() -> AutoConfigurationCandidates.scan(List.of(notAJar)));
	}

	private Path directoryWithImports(String name, String content) throws IOException {
		Path root = this.tempDir.resolve(name);
		Path imports = root.resolve(IMPORTS);
		Files.createDirectories(imports.getParent());
		Files.writeString(imports, content, StandardCharsets.UTF_8);
		return root;
	}

	private Path jarWithImports(String name, String content) throws IOException {
		Path jar = this.tempDir.resolve(name);
		try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
			jos.putNextEntry(new JarEntry(IMPORTS));
			jos.write(content.getBytes(StandardCharsets.UTF_8));
			jos.closeEntry();
		}
		return jar;
	}

}
