package com.fortytwotalents.optimizer.gradle;

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
                    id 'com.fortytwotalents.autoconfiguration-optimizer'
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
    void pluginRegistersInjectTask() throws IOException {
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'com.fortytwotalents.autoconfiguration-optimizer'
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

        assertThat(result.getOutput()).contains("injectOptimizerCore");
    }

    @Test
    void pluginAppliesWithoutErrors() throws IOException {
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'com.fortytwotalents.autoconfiguration-optimizer'
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
}
