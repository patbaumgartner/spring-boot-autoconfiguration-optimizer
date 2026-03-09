package com.fortytwotalents.optimizer.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the benchmark utilities.
 * The full benchmarks are run separately via: mvn verify -Dbenchmark.skip=false
 */
class StartupTimeBenchmarkTest {

    @Test
    void benchmarkClassCanBeInstantiated() {
        StartupTimeBenchmark benchmark = new StartupTimeBenchmark();
        assertThat(benchmark).isNotNull();
    }
}
