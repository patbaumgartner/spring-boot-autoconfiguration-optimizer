package com.fortytwotalents.optimizer.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MainClassFinderTest {

    @TempDir
    Path tempDir;

    @Test
    void findsClassAnnotatedWithSpringBootApplication() throws IOException {
        Path classDir = createClassFile("com/example/MyApplication.class",
                "Lorg/springframework/boot/autoconfigure/SpringBootApplication;");

        Optional<String> result = MainClassFinder.findMainClass(List.of(classDir.toFile()));

        assertThat(result).isPresent().contains("com.example.MyApplication");
    }

    @Test
    void findsClassAnnotatedWithSpringBootConfiguration() throws IOException {
        Path classDir = createClassFile("com/example/MyApp.class",
                "Lorg/springframework/boot/SpringBootConfiguration;");

        Optional<String> result = MainClassFinder.findMainClass(List.of(classDir.toFile()));

        assertThat(result).isPresent().contains("com.example.MyApp");
    }

    @Test
    void returnsEmptyWhenNoAnnotatedClassFound() throws IOException {
        Path classDir = tempDir.resolve("classes");
        Files.createDirectories(classDir.resolve("com/example"));
        Files.write(classDir.resolve("com/example/Other.class"),
                new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});

        Optional<String> result = MainClassFinder.findMainClass(List.of(classDir.toFile()));

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyForEmptyDirectoryList() {
        Optional<String> result = MainClassFinder.findMainClass(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyForNonExistentDirectory() {
        Optional<String> result = MainClassFinder.findMainClass(List.of(new File("/nonexistent/path")));

        assertThat(result).isEmpty();
    }

    @Test
    void scansMultipleDirectories() throws IOException {
        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectories(emptyDir);

        Path classDir = createClassFile("com/example/App.class",
                "Lorg/springframework/boot/autoconfigure/SpringBootApplication;");

        Optional<String> result = MainClassFinder.findMainClass(List.of(emptyDir.toFile(), classDir.toFile()));

        assertThat(result).isPresent().contains("com.example.App");
    }

    /**
     * Creates a synthetic .class file containing the given annotation descriptor bytes,
     * returning the base classes directory.
     */
    private Path createClassFile(String relativePath, String annotationDescriptor) throws IOException {
        Path classDir = tempDir.resolve("classes-" + relativePath.hashCode());
        Path classFile = classDir.resolve(relativePath);
        Files.createDirectories(classFile.getParent());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Write a Java class file magic number
        baos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        // Padding bytes (minor/major version)
        baos.write(new byte[]{0, 0, 0, 61});
        // Write the annotation descriptor so the byte scan finds it
        baos.write(annotationDescriptor.getBytes(StandardCharsets.UTF_8));

        Files.write(classFile, baos.toByteArray());
        return classDir;
    }

}
