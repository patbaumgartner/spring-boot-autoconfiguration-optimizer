# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Native image benchmark script (`benchmarks/scripts/run-benchmarks-native.sh`) and CI
  workflow (`benchmarks-native.yml`) to measure and document the optimizer's impact on
  GraalVM native image startup time.
- Native Maven profile (`-Pnative`) in the PetClinic sample for building native images
  with `native-maven-plugin` and Spring Boot AOT processing.
- Clarified README: the optimizer's primary benefit is for JVM deployments; native images
  already start in 50-400 ms via AOT pre-resolution and gain little to no additional
  improvement from the optimizer's runtime filter.

## [1.0.0] - 2026-03-20

### Added

- Core library with `OptimizedAutoConfigurationImportFilter` that reads the training file
  and filters auto-configuration candidates at import time using Spring Boot's
  `AutoConfigurationImportFilter` extension point.
- Core library with `TrainingRunApplicationListener` that captures matched
  auto-configurations from `ConditionEvaluationReport` and writes the training properties
  file, handling both class-level and method-level condition keys.
- `AutoConfigurationOptimizerProperties` for configuring the optimizer via
  `application.properties`, system properties, or environment variables.
- `AutoConfigurationOptimizerRuntimeHints` for GraalVM native image compatibility:
  resource hints for the properties file and reflection hints for the filter and listener.
- Maven plugin (`spring-boot-autoconfiguration-optimizer-maven-plugin`) with:
  - `train` goal — forks a training-run JVM process and copies the resulting properties
    file into `target/classes/META-INF/`.
  - `inject` goal — embeds the optimizer core classes into the project's compile output
    directory so no explicit core dependency is needed.
  - `verify` goal — asserts that the training file exists (useful in CI pipelines).
  - Maven plugin prefix `autoconfiguration-optimizer` for short-form invocation:
    `mvn autoconfiguration-optimizer:train`.
- Gradle plugin (`com.fortytwotalents.autoconfiguration-optimizer`) with:
  - `trainAutoconfiguration` task — runs the training subprocess.
  - `copyAutoconfigurationOptimizerFile` task — copies the generated file to the main
    output directory.
  - `injectOptimizerCore` task — injects core classes before packaging.
  - Automatic wiring to `bootJar`, `bootWar`, and `resolveMainClassName` Spring Boot tasks.
- `MainClassFinder` utility (Maven and Gradle) that scans bytecode for
  `@SpringBootApplication` without loading classes into the JVM, supporting custom
  annotation hierarchies.
- PetClinic-like integration test application for both Maven and Gradle end-to-end
  validation.
- Automated startup time benchmarks with comparison reports (with/without optimizer).
- Full CI matrix covering Java 17, 21, and 25.
- Automated release pipeline: Maven Central + Gradle Plugin Portal publishing.
- GraalVM native image support tested in the recommended build order: train → inject →
  `process-aot` → `native:compile`.

[Unreleased]: https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/releases/tag/v1.0.0
