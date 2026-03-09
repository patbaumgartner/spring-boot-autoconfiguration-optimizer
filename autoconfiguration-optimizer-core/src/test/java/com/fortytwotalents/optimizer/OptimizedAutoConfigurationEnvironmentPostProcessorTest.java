package com.fortytwotalents.optimizer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizedAutoConfigurationEnvironmentPostProcessorTest {

    private final OptimizedAutoConfigurationEnvironmentPostProcessor processor =
            new OptimizedAutoConfigurationEnvironmentPostProcessor();

    @Test
    void parseConfigurationList_parsesCommaSeparatedList() {
        Set<String> result = processor.parseConfigurationList(
                "com.example.FooAutoConfiguration, com.example.BarAutoConfiguration");
        assertThat(result)
                .contains("com.example.FooAutoConfiguration")
                .contains("com.example.BarAutoConfiguration")
                .hasSize(2);
    }

    @Test
    void parseConfigurationList_handlesWhitespaceAroundEntries() {
        Set<String> result = processor.parseConfigurationList(
                "  com.example.FooAutoConfiguration  ,  com.example.BarAutoConfiguration  ");
        assertThat(result)
                .contains("com.example.FooAutoConfiguration")
                .contains("com.example.BarAutoConfiguration");
    }

    @Test
    void parseConfigurationList_filtersEmptyEntries() {
        Set<String> result = processor.parseConfigurationList("com.example.FooAutoConfiguration,,com.example.BarAutoConfiguration");
        assertThat(result).hasSize(2);
    }

    @Test
    void applyExclusions_setsExcludeProperty() {
        MockEnvironment environment = new MockEnvironment();
        List<String> toExclude = List.of(
                "com.example.UnusedAutoConfiguration",
                "com.example.AnotherUnusedAutoConfiguration"
        );

        processor.applyExclusions(environment, toExclude);

        String exclusions = environment.getProperty(OptimizedAutoConfigurationEnvironmentPostProcessor.EXCLUDE_PROPERTY);
        assertThat(exclusions).isNotNull();
        assertThat(exclusions).contains("com.example.UnusedAutoConfiguration");
        assertThat(exclusions).contains("com.example.AnotherUnusedAutoConfiguration");
    }

    @Test
    void applyExclusions_preservesExistingExclusions() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(OptimizedAutoConfigurationEnvironmentPostProcessor.EXCLUDE_PROPERTY,
                "com.example.AlreadyExcluded");

        List<String> toExclude = List.of("com.example.NewExclusion");

        processor.applyExclusions(environment, toExclude);

        String exclusions = environment.getProperty(OptimizedAutoConfigurationEnvironmentPostProcessor.EXCLUDE_PROPERTY);
        assertThat(exclusions).contains("com.example.AlreadyExcluded");
        assertThat(exclusions).contains("com.example.NewExclusion");
    }

    @Test
    void loadAllAvailableAutoConfigurations_loadsFromClasspath() throws Exception {
        List<String> configs = processor.loadAllAvailableAutoConfigurations();
        // Spring Boot auto-configurations should be on the test classpath
        assertThat(configs).isNotEmpty();
        assertThat(configs).anyMatch(c -> c.contains("springframework.boot.autoconfigure"));
    }
}
