# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/releases/tag/v1.0.0
