package com.fortytwotalents.optimizer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying the autoconfiguration optimizer in training mode.
 */
@SpringBootTest(
    classes = AutoConfigurationOptimizerIntegrationTest.TestApp.class,
    properties = {
        "autoconfiguration.optimizer.training-run=true",
        "autoconfiguration.optimizer.exit-after-training=false"
    }
)
class AutoConfigurationOptimizerIntegrationTest {

    @Autowired
    private AutoConfigurationOptimizerProperties properties;

    @Autowired(required = false)
    private TrainingRunApplicationListener trainingRunApplicationListener;

    @Test
    void trainingRunListenerIsRegisteredWhenTrainingModeEnabled() {
        assertThat(trainingRunApplicationListener).isNotNull();
    }

    @Test
    void propertiesAreLoaded() {
        assertThat(properties).isNotNull();
        assertThat(properties.isTrainingRun()).isTrue();
    }

    @SpringBootApplication
    static class TestApp {
    }
}
