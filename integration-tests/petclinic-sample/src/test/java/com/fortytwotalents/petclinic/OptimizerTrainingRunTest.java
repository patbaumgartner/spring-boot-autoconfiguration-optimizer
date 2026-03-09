package com.fortytwotalents.petclinic;

import com.fortytwotalents.optimizer.AutoConfigurationOptimizerProperties;
import com.fortytwotalents.optimizer.TrainingRunApplicationListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that verifies the optimizer training run works with the PetClinic
 * application.
 */
@SpringBootTest
@TestPropertySource(properties = { "autoconfiguration.optimizer.training-run=true",
		"autoconfiguration.optimizer.exit-after-training=false" })
class OptimizerTrainingRunTest {

	@Autowired
	private AutoConfigurationOptimizerProperties optimizerProperties;

	@Autowired
	private TrainingRunApplicationListener trainingRunApplicationListener;

	@Test
	void trainingRunListenerIsActive() {
		assertThat(trainingRunApplicationListener).isNotNull();
		assertThat(optimizerProperties.isTrainingRun()).isTrue();
	}

}
