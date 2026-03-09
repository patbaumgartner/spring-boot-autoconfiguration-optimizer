package com.fortytwotalents.optimizer;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Spring Boot Autoconfiguration Optimizer.
 *
 * <p>Registers the {@link TrainingRunApplicationListener} when training mode is enabled
 * ({@code autoconfiguration.optimizer.training-run=true}).
 */
@AutoConfiguration
@EnableConfigurationProperties(AutoConfigurationOptimizerProperties.class)
public class AutoConfigurationOptimizerAutoConfiguration {

    /**
     * Creates the {@link TrainingRunApplicationListener} bean that records loaded
     * auto-configurations during a training run.
     *
     * @param properties              optimizer properties
     * @param conditionEvaluationReport Spring Boot's condition evaluation report
     * @return the training run listener
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "autoconfiguration.optimizer", name = "training-run", havingValue = "true")
    public TrainingRunApplicationListener trainingRunApplicationListener(
            AutoConfigurationOptimizerProperties properties,
            ConditionEvaluationReport conditionEvaluationReport) {
        return new TrainingRunApplicationListener(properties, conditionEvaluationReport);
    }
}
