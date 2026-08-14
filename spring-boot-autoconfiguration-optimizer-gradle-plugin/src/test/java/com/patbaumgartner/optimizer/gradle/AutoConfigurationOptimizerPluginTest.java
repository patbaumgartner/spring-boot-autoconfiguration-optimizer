package com.patbaumgartner.optimizer.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AutoConfigurationOptimizerPluginTest {

    @TempDir
    Path projectDir;

    @Test
    void pluginRegistersTrainTask() throws IOException {
        // Create a minimal build.gradle
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'com.patbaumgartner.autoconfiguration-optimizer'
                }
                """);

        Files.writeString(projectDir.resolve("settings.gradle"), """
                rootProject.name = 'test-project'
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("tasks", "--all")
                .withPluginClasspath()
                .build();

        assertThat(result.getOutput()).contains("trainAutoconfiguration");
        assertThat(result.getOutput()).contains("copyAutoconfigurationOptimizerFile");
    }

    @Test
    void pluginAddsTheOptimizerCoreToTheRuntimeClasspath() throws IOException {
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'com.patbaumgartner.autoconfiguration-optimizer'
                }
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                tasks.register('printRuntimeDependencies') {
                    def coordinates = configurations.runtimeClasspath.allDependencies.collect {
                        "${it.group}:${it.name}:${it.version}"
                    }
                    doLast { coordinates.each { println "runtime-dependency: ${it}" } }
                }
                """);

        Files.writeString(projectDir.resolve("settings.gradle"), """
                rootProject.name = 'test-project'
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("printRuntimeDependencies")
                .withPluginClasspath()
                .build();

        assertThat(result.getOutput())
                .contains("runtime-dependency: com.patbaumgartner:autoconfiguration-optimizer-core:");
    }

    @Test
    void pluginNoLongerRegistersAnInjectTask() throws IOException {
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'com.patbaumgartner.autoconfiguration-optimizer'
                }
                """);

        Files.writeString(projectDir.resolve("settings.gradle"), """
                rootProject.name = 'test-project'
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("tasks", "--all")
                .withPluginClasspath()
                .build();

        assertThat(result.getOutput()).doesNotContain("injectOptimizerCore");
    }

    @Test
    void pluginAppliesWithoutErrors() throws IOException {
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'com.patbaumgartner.autoconfiguration-optimizer'
                }
                
                autoconfigurationOptimizer {
                    mainClass = 'com.example.TestApplication'
                    timeout = 60
                }
                """);

        Files.writeString(projectDir.resolve("settings.gradle"), """
                rootProject.name = 'test-project'
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("help")
                .withPluginClasspath()
                .build();

        assertThat(result.getOutput()).doesNotContain("ERROR");
    }

    @Test
    void bootJarDependsOnTheTrainingFileCopy() throws IOException {
        // Minimal settings that pull Spring Boot from Gradle Plugin Portal
        Files.writeString(projectDir.resolve("settings.gradle"), """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = 'test-project'
                """);

        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'org.springframework.boot' version '4.1.0'
                    id 'com.patbaumgartner.autoconfiguration-optimizer'
                }
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                """);

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("bootJar", "--dry-run")
                .withPluginClasspath()
                .build();

        assertThat(result.getOutput()).contains(":copyAutoconfigurationOptimizerFile");
    }
}
