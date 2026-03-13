package com.fortytwotalents.optimizer.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JMH benchmark that measures Spring Boot application startup time with and without the
 * autoconfiguration optimizer.
 *
 * <p>
 * This benchmark:
 * <ol>
 * <li>Starts the PetClinic sample application without optimization (baseline)</li>
 * <li>Starts the PetClinic sample application with the optimizer enabled</li>
 * <li>Reports the startup time difference</li>
 * </ol>
 *
 * <p>
 * Run via: {@code java -jar target/benchmarks.jar}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = { "-Xmx256m" })
public class StartupTimeBenchmark {

	private static final Pattern STARTED_PATTERN = Pattern.compile("Started .+ in ([\\d.]+) seconds");

	private String javaExecutable;

	private String petclinicJar;

	@Setup
	public void setup() {
		javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();

		// Find the PetClinic JAR
		String jarPath = System.getProperty("petclinic.jar");
		if (jarPath != null && new File(jarPath).exists()) {
			petclinicJar = jarPath;
		}
	}

	/**
	 * Measures startup time without the autoconfiguration optimizer (baseline).
	 */
	@Benchmark
	public double baselineStartup() throws IOException, InterruptedException {
		return measureStartupTime(List.of("-Dautoconfiguration.optimizer.enabled=false"));
	}

	/**
	 * Measures startup time with the autoconfiguration optimizer enabled.
	 */
	@Benchmark
	public double optimizedStartup() throws IOException, InterruptedException {
		return measureStartupTime(List.of("-Dautoconfiguration.optimizer.enabled=true"));
	}

	private double measureStartupTime(List<String> extraArgs) throws IOException, InterruptedException {
		if (petclinicJar == null) {
			return -1;
		}

		List<String> command = new ArrayList<>();
		command.add(javaExecutable);
		command.addAll(extraArgs);
		command.add("-jar");
		command.add(petclinicJar);
		command.add("--spring.main.banner-mode=off");
		command.add("--server.port=0");

		ProcessBuilder pb = new ProcessBuilder(command);
		pb.redirectErrorStream(true);
		Process process = pb.start();

		double startupTime = -1;
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				Matcher matcher = STARTED_PATTERN.matcher(line);
				if (matcher.find()) {
					startupTime = Double.parseDouble(matcher.group(1)) * 1000;
					break;
				}
			}
		}
		finally {
			process.destroyForcibly();
		}

		return startupTime;
	}

	/**
	 * Main method to run the benchmarks programmatically.
	 */
	public static void main(String[] args) throws Exception {
		Options opt = new OptionsBuilder().include(StartupTimeBenchmark.class.getSimpleName())
			.warmupIterations(3)
			.measurementIterations(5)
			.forks(1)
			.resultFormat(ResultFormatType.JSON)
			.result("benchmark-results.json")
			.build();

		new Runner(opt).run();
	}

}
