# Spring Boot Autoconfiguration Optimizer

[![CI](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/actions/workflows/ci.yml/badge.svg)](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/actions/workflows/ci.yml)
[![Benchmarks](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/actions/workflows/benchmarks.yml/badge.svg)](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/actions/workflows/benchmarks.yml)
[![CodeQL](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/actions/workflows/codeql.yml/badge.svg)](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/actions/workflows/codeql.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.fortytwotalents/autoconfiguration-optimizer-core.svg?label=Maven%20Central)](https://search.maven.org/artifact/com.fortytwotalents/autoconfiguration-optimizer-core)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/com.fortytwotalents.autoconfiguration-optimizer.svg)](https://plugins.gradle.org/plugin/com.fortytwotalents.autoconfiguration-optimizer)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen.svg)](https://spring.io/projects/spring-boot)

> **Dramatically reduce Spring Boot startup time** by automatically detecting and excluding unused auto-configurations via a training run approach.

## 🚀 Benchmark Results

Latest benchmark results running against a [Spring PetClinic](integration-tests/petclinic-sample)-like application (Web + JPA + Actuator + Cache + Validation):

> See the [latest benchmark report](../../actions/workflows/benchmarks.yml) in GitHub Actions for up-to-date numbers.

| Configuration | Startup Time | Improvement |
|---|---|---|
| Baseline (no optimizer) | ~2500ms | — |
| With optimizer | ~1600ms | ~36% faster |

*Numbers are illustrative; see the CI benchmark artifacts for exact measurements on your setup.*

## What It Does

Spring Boot loads hundreds of auto-configurations at startup. Most are filtered out by their `@Conditional` annotations, but the filtering itself takes time. This optimizer:

1. **Training Run**: Starts your application once and records which auto-configurations **actually passed** their conditions.
2. **Optimization**: On subsequent starts, directly excludes all unused auto-configurations via `spring.autoconfigure.exclude`, skipping the condition evaluation entirely.
3. **Zero Dev Impact**: During development (no properties file present), all auto-configurations run normally.

## Quick Start

### Maven

```xml
<!-- Add the core library -->
<dependency>
    <groupId>com.fortytwotalents</groupId>
    <artifactId>autoconfiguration-optimizer-core</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Add the plugin -->
<plugin>
    <groupId>com.fortytwotalents</groupId>
    <artifactId>spring-boot-autoconfiguration-optimizer-maven-plugin</artifactId>
    <version>1.0.0</version>
    <executions>
        <execution>
            <goals><goal>train</goal></goals>
        </execution>
    </executions>
    <configuration>
        <!-- Required: fully-qualified main class name.
             Omit only if the project has a 'start-class' property or a single
             @SpringBootApplication class that can be auto-detected in compiled output. -->
        <mainClass>com.example.MyApplication</mainClass>

        <!-- Optional: extra JVM arguments passed to the training-run process -->
        <jvmArguments>
            <jvmArgument>-Xmx512m</jvmArgument>
            <jvmArgument>-Dspring.profiles.active=training</jvmArgument>
        </jvmArguments>

        <!-- Optional: timeout in seconds (default: 120) -->
        <timeout>120</timeout>
    </configuration>
</plugin>
```

Or run manually:

```bash
# Run the training run
mvn com.fortytwotalents:spring-boot-autoconfiguration-optimizer-maven-plugin:train \
  -Dautoconfiguration.optimizer.mainClass=com.example.MyApplication

# This generates src/main/resources/META-INF/autoconfiguration-optimizer.properties
# Commit this file to your repository!
```

### Gradle

```groovy
plugins {
    id 'com.fortytwotalents.autoconfiguration-optimizer' version '1.0.0'
}

autoconfigurationOptimizer {
    // Required: fully-qualified main class name.
    // Omit only when a single @SpringBootApplication class can be auto-detected
    // in the compiled output.
    mainClass = 'com.example.MyApplication'

    // Optional: extra JVM arguments passed to the training-run process
    jvmArguments = ['-Xmx512m', '-Dspring.profiles.active=training']

    // Optional: timeout in seconds (default: 120)
    timeout = 120
}
```

```bash
# Run the training run
./gradlew trainAutoconfiguration copyAutoconfigurationOptimizerFile
```

## How It Works

```
Training Run                    Production Run
────────────────                ────────────────────────────────
Application starts              EnvironmentPostProcessor reads
with all auto-configs     →     META-INF/autoconfiguration-optimizer.properties
                                            │
ConditionEvaluationReport       Sets spring.autoconfigure.exclude
records matched configs   →     for all configs NOT in the list
                                            │
Writes                          Spring Boot skips condition
autoconfiguration-              evaluation for excluded configs
optimizer.properties
```

## GraalVM Native Image & AOT Support

The optimizer is fully compatible with GraalVM native images and Spring Boot's AOT processing:

- **Resource hints** are registered via `AutoConfigurationOptimizerRuntimeHints` to include the properties file in native images
- **Reflection hints** are registered for the `EnvironmentPostProcessor`
- Run the training run **before** `spring-boot:process-aot` to get maximum benefit

```bash
# Recommended build order for native images:
mvn autoconfiguration-optimizer:train          # 1. Training run
mvn spring-boot:process-aot                   # 2. AOT processing
mvn -Pnative native:compile                   # 3. Native compilation
```

## Configuration Reference

### Core Library Properties

| Property | Default | Description |
|---|---|---|
| `autoconfiguration.optimizer.enabled` | `true` | Enable/disable the optimizer |
| `autoconfiguration.optimizer.training-run` | `false` | Enable training mode |
| `autoconfiguration.optimizer.output-file` | `autoconfiguration-optimizer.properties` | Output file name |
| `autoconfiguration.optimizer.output-directory` | `.` | Output directory for training |
| `autoconfiguration.optimizer.exit-after-training` | `false` | Exit JVM after training |

### Maven Plugin Parameters (`train` goal)

| Parameter | Property | Default | Description |
|---|---|---|---|
| `mainClass` | `autoconfiguration.optimizer.mainClass` | auto-detected | **Required** — fully-qualified main class. Auto-detected from `start-class` property or `@SpringBootApplication` scan when omitted. |
| `jvmArguments` | `autoconfiguration.optimizer.jvmArguments` | _(none)_ | Additional JVM arguments passed to the training-run process. |
| `jar` | `autoconfiguration.optimizer.jar` | _(none)_ | Spring Boot executable JAR to run. When set, `mainClass` is ignored. |
| `timeout` | `autoconfiguration.optimizer.timeout` | `120` | Training run timeout in seconds. |
| `targetDirectory` | `autoconfiguration.optimizer.targetDirectory` | `src/main/resources/META-INF` | Directory where the properties file is copied after training. |
| `outputFile` | `autoconfiguration.optimizer.outputFile` | `autoconfiguration-optimizer.properties` | Name of the generated properties file. |
| `workingDirectory` | `autoconfiguration.optimizer.workingDirectory` | `${project.build.directory}` | Working directory for the training process. |
| `skip` | `autoconfiguration.optimizer.skip` | `false` | Skip the training run. |

### Gradle Plugin Extension (`autoconfigurationOptimizer`)

| Property | Default | Description |
|---|---|---|
| `mainClass` | auto-detected | **Required** — fully-qualified main class. Auto-detected from `@SpringBootApplication` scan when omitted. |
| `jvmArguments` | _(none)_ | Additional JVM arguments passed to the training-run process. |
| `jar` | _(none)_ | Spring Boot executable JAR to run. When set, `mainClass` is ignored. |
| `timeout` | `120` | Training run timeout in seconds. |
| `targetDirectory` | `src/main/resources/META-INF` | Directory where the properties file is copied after training. |
| `outputFile` | `autoconfiguration-optimizer.properties` | Name of the generated properties file. |
| `skip` | `false` | Skip the training run. |

## Spring Boot Compatibility

| Spring Boot | Java | Build Tool | Status |
|---|---|---|---|
| 4.0.x | 21, 25 | Maven, Gradle 9 | ✅ Default |

> **Spring Boot 4.0** requires **Java 21 minimum** and is the default target for this library.

## Running Benchmarks

```bash
# Build everything
mvn package -DskipTests

# Run benchmarks (requires built PetClinic JAR)
chmod +x benchmarks/scripts/run-benchmarks.sh
./benchmarks/scripts/run-benchmarks.sh \
  integration-tests/petclinic-sample/target/autoconfiguration-optimizer-petclinic-sample-*.jar

# View report
cat benchmarks/results/benchmark-report.md
```

Benchmark reports are also generated automatically in CI and available as [GitHub Actions artifacts](../../actions/workflows/benchmarks.yml).

## Project Structure

```
spring-boot-autoconfiguration-optimizer/
├── autoconfiguration-optimizer-core/          # Core library
├── spring-boot-autoconfiguration-optimizer-maven-plugin/  # Maven plugin
├── spring-boot-autoconfiguration-optimizer-gradle-plugin/ # Gradle plugin
├── integration-tests/
│   └── petclinic-sample/                      # PetClinic-like integration test app
└── benchmarks/                                # JMH startup benchmarks + scripts
```

## Contributing

See [CODE_OF_CONDUCT.md](.github/CODE_OF_CONDUCT.md) for community standards.

## Security

See [SECURITY.md](SECURITY.md) for how to report security vulnerabilities.

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.
