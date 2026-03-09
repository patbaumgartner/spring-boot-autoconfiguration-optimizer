package com.fortytwotalents.optimizer;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizedAutoConfigurationImportFilterTest {

	@Test
	void match_returnsAllTrueWhenEnvironmentIsNull() {
		OptimizedAutoConfigurationImportFilter filter = new OptimizedAutoConfigurationImportFilter();
		// No environment set — filter should pass everything through
		String[] candidates = { "com.example.FooAutoConfiguration", "com.example.BarAutoConfiguration" };

		boolean[] result = filter.match(candidates, null);

		assertThat(result).containsOnly(true);
	}

	@Test
	void match_returnsAllTrueWhenDisabled() {
		OptimizedAutoConfigurationImportFilter filter = new OptimizedAutoConfigurationImportFilter();
		MockEnvironment env = new MockEnvironment();
		env.setProperty("autoconfiguration.optimizer.enabled", "false");
		filter.setEnvironment(env);

		String[] candidates = { "com.example.FooAutoConfiguration", "com.example.BarAutoConfiguration" };

		boolean[] result = filter.match(candidates, null);

		assertThat(result).containsOnly(true);
	}

	@Test
	void match_returnsAllTrueWhenTrainingRunActive() {
		OptimizedAutoConfigurationImportFilter filter = new OptimizedAutoConfigurationImportFilter();
		MockEnvironment env = new MockEnvironment();
		env.setProperty("autoconfiguration.optimizer.training-run", "true");
		filter.setEnvironment(env);

		String[] candidates = { "com.example.FooAutoConfiguration", "com.example.BarAutoConfiguration" };

		boolean[] result = filter.match(candidates, null);

		assertThat(result).containsOnly(true);
	}

	@Test
	void match_returnsAllTrueWhenNoTrainingFile() {
		OptimizedAutoConfigurationImportFilter filter = new OptimizedAutoConfigurationImportFilter();
		filter.setEnvironment(new MockEnvironment());
		// No training file on test classpath at the expected location — returns null
		// meaning "allow all"

		String[] candidates = { "com.example.FooAutoConfiguration", "com.example.BarAutoConfiguration" };

		boolean[] result = filter.match(candidates, null);

		// When getAllowedConfigurations() returns null, everything passes through
		assertThat(result).containsOnly(true);
	}

	@Test
	void match_filtersConfigurationsNotInAllowedSet() {
		OptimizedAutoConfigurationImportFilter filter = Mockito.spy(new OptimizedAutoConfigurationImportFilter());
		filter.setEnvironment(new MockEnvironment());
		Mockito.doReturn(Set.of("com.example.FooAutoConfiguration")).when(filter).getAllowedConfigurations();

		String[] candidates = { "com.example.FooAutoConfiguration", "com.example.BarAutoConfiguration",
				"com.example.BazAutoConfiguration" };

		boolean[] result = filter.match(candidates, null);

		assertThat(result[0]).isTrue(); // FooAutoConfiguration is in the training set
		assertThat(result[1]).isFalse(); // BarAutoConfiguration is NOT in the training
											// set
		assertThat(result[2]).isFalse(); // BazAutoConfiguration is NOT in the training
											// set
	}

	@Test
	void match_passesNullCandidatesThroughUnchanged() {
		OptimizedAutoConfigurationImportFilter filter = Mockito.spy(new OptimizedAutoConfigurationImportFilter());
		filter.setEnvironment(new MockEnvironment());
		Mockito.doReturn(Set.of("com.example.FooAutoConfiguration")).when(filter).getAllowedConfigurations();

		// null entries represent candidates already removed by an earlier filter
		String[] candidates = { null, "com.example.BarAutoConfiguration" };

		boolean[] result = filter.match(candidates, null);

		assertThat(result[0]).isTrue(); // null candidate is passed through
		assertThat(result[1]).isFalse(); // BarAutoConfiguration is not in the training
											// set
	}

	@Test
	void getAllowedConfigurations_loadsOnlyOnce() {
		OptimizedAutoConfigurationImportFilter filter = new OptimizedAutoConfigurationImportFilter();
		filter.setEnvironment(new MockEnvironment());

		// Call twice — the training file loading should happen only once
		Set<String> first = filter.getAllowedConfigurations();
		Set<String> second = filter.getAllowedConfigurations();

		assertThat(first).isSameAs(second);
	}

	@Test
	void match_allowsAllConfigurationsWhenAllAreInTrainingSet() {
		OptimizedAutoConfigurationImportFilter filter = Mockito.spy(new OptimizedAutoConfigurationImportFilter());
		filter.setEnvironment(new MockEnvironment());
		Set<String> allowed = Set.of("com.example.FooAutoConfiguration", "com.example.BarAutoConfiguration");
		Mockito.doReturn(allowed).when(filter).getAllowedConfigurations();

		String[] candidates = { "com.example.FooAutoConfiguration", "com.example.BarAutoConfiguration" };

		boolean[] result = filter.match(candidates, null);

		assertThat(result).containsOnly(true);
	}

}
