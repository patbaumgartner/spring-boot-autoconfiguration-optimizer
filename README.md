# Spring Boot Autoconfiguration Optimizer

[![CI](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/actions/workflows/ci.yml/badge.svg)](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/com.fortytwotalents/autoconfiguration-optimizer-core.svg)](https://search.maven.org/artifact/com.fortytwotalents/autoconfiguration-optimizer-core)

Analyzes and optimizes Spring Boot auto-configurations to improve application startup time by excluding unused auto-configurations based on a training run.

## What It Does

Spring Boot loads hundreds of auto-configurations at startup, most of which are not used by your application. This tool:

1. **Training Run**: Starts your application once with all auto-configurations enabled and records which ones were actually loaded.
2. **Optimization**: On subsequent starts, excludes all unused auto-configurations, significantly reducing startup time.

## Quick Start

### Maven

Add the core library to your Spring Boot application:

```xml
<dependency>
    <groupId>com.fortytwotalents</groupId>
    <artifactId>autoconfiguration-optimizer-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Run the training goal:

```bash
mvn com.fortytwotalents:spring-boot-autoconfiguration-optimizer-maven-plugin:train
```

### Gradle

```groovy
plugins {
    id 'com.fortytwotalents.autoconfiguration-optimizer' version '1.0.0'
}

autoconfigurationOptimizer {
    mainClass = 'com.example.MyApplication'
}
```

Run the training task:

```bash
./gradlew trainAutoconfiguration copyAutoconfigurationOptimizerFile
```

## How the Training Run Works

1. The Maven/Gradle plugin starts your application with `-Dautoconfiguration.optimizer.training-run=true`
2. `TrainingRunApplicationListener` captures the `ConditionEvaluationReport` after startup
3. It writes a properties file listing all loaded auto-configurations
4. The file is copied to `src/main/resources/META-INF/autoconfiguration-optimizer.properties`
5. On the next startup, `OptimizedAutoConfigurationEnvironmentPostProcessor` reads this file and sets `spring.autoconfigure.exclude` to skip unused configurations

## Configuration Reference

| Property | Default | Description |
|---|---|---|
| `autoconfiguration.optimizer.enabled` | `true` | Enable/disable the optimizer |
| `autoconfiguration.optimizer.training-run` | `false` | Enable training mode |
| `autoconfiguration.optimizer.output-file` | `autoconfiguration-optimizer.properties` | Output file name |
| `autoconfiguration.optimizer.output-directory` | `.` | Output directory |
| `autoconfiguration.optimizer.exit-after-training` | `false` | Exit after training completes |

## Spring Boot Compatibility

| Spring Boot | Java | Status |
|---|---|---|
| 3.4.x | 17, 21, 24 | ✅ Supported |
| 3.3.x | 17, 21 | ✅ Supported |

## Contributing

See [CONTRIBUTING.md](.github/CONTRIBUTING.md) for development setup and contribution guidelines.

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.
