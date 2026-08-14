# Spring Boot Autoconfiguration Optimizer

[![CI](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/actions/workflows/ci.yml/badge.svg)](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/actions/workflows/ci.yml)
[![Benchmarks](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/actions/workflows/benchmarks.yml/badge.svg)](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/actions/workflows/benchmarks.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.patbaumgartner/autoconfiguration-optimizer-core.svg?label=Maven%20Central)](https://search.maven.org/artifact/com.patbaumgartner/autoconfiguration-optimizer-core)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/com.patbaumgartner.autoconfiguration-optimizer.svg)](https://plugins.gradle.org/plugin/com.patbaumgartner.autoconfiguration-optimizer)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-brightgreen.svg)](https://spring.io/projects/spring-boot)

> Reduce Spring Boot startup time by automatically detecting and excluding unused auto-configurations using a one-time training run.

## Why Bother?

Spring Boot evaluates hundreds of `@Conditional` annotations at every startup, even for auto-configurations that will never apply to your application. This optimizer records which ones actually matched during a training run and permanently skips the rest on every subsequent start.

The result: fewer condition evaluations, faster startup, and zero changes to your application code.

## How It Works

1. **Training Run**: Start your application once with training mode enabled. The optimizer records every registered auto-configuration that did **not** apply and writes that list to `META-INF/autoconfiguration-optimizer.properties`. Commit this file so it is baked into subsequent builds. **This step is required to get any optimization benefit** - without the training file, the optimizer is a no-op.
2. **Depend on the core**: The `autoconfiguration-optimizer-core` library contains the filter that reads the file. Gradle users get it automatically; Maven users declare it once (see below).
3. **Subsequent Starts**: An `AutoConfigurationImportFilter` reads the file at startup and skips exactly those auto-configurations, so Spring Boot never loads them or evaluates their conditions.
4. **Safe by Default**: A missing, unreadable, or unrecognised training file means no filtering at all, and the application starts exactly as it would without the optimizer.

```
Training Run                    Production Run
────────────────                ──────────────────────────────────
App starts normally             AutoConfigurationImportFilter reads
with all auto-configs           META-INF/autoconfiguration-
                                optimizer.properties
ConditionEvaluationReport                  │
records which configs           Skips exactly the auto-configs
applied                         recorded as unused
        │                                  │
Writes the ones that did        Spring Boot never loads them or
NOT apply to                    evaluates their conditions
autoconfiguration-
optimizer.properties
```

### Why the File Records What Was *Not* Used

Recording exclusions rather than inclusions is what makes an out-of-date training file safe.

If the file listed the auto-configurations to keep, then every candidate missing from it would be skipped - including one introduced by a dependency you added after training. The application would still start, but that starter's beans, endpoints and repositories would silently be absent. Recording exclusions inverts that: anything the optimizer was not explicitly told to skip is loaded, so an unknown candidate behaves normally and the only cost is that it is not optimized yet.

It also removes work from startup. Because the filter never needs to know the full candidate set, it does not re-read every `AutoConfiguration.imports` file on the classpath - which a startup optimizer really should not be doing.

### When You Must Re-Train

> [!IMPORTANT]
> Exclusions are decided by the conditions evaluated during the training run. Conditions can start matching **without any auto-configuration name changing** - for example, removing a dependency can make a `@ConditionalOnMissingClass` auto-configuration applicable, and it will still be excluded.

Re-run training whenever dependencies, profiles, or the runtime configuration change, and train in a configuration that matches production. The `verify` goal catches the classpath half of this automatically: it recomputes a digest over the auto-configuration candidates and fails the build when they no longer match the ones recorded during training.

```bash
mvn com.patbaumgartner:spring-boot-autoconfiguration-optimizer-maven-plugin:verify

# [ERROR] ... is stale. It was recorded against 115 auto-configuration candidates,
#         but this project now has 118. Re-run '...:train' ...
```

### Why `AutoConfigurationImportFilter` Instead of `spring.autoconfigure.exclude`

The optimizer uses Spring Boot's `AutoConfigurationImportFilter` extension point rather than setting `spring.autoconfigure.exclude`. This is more efficient because:

- **Single pass**: The list of auto-configuration candidates is loaded only once by Spring Boot's `AutoConfigurationImportSelector`, not twice (once in an `EnvironmentPostProcessor` and once again inside the selector).
- **No string manipulation**: Filtering is done via a simple `boolean[]` array operation on the candidate strings with no comma-separated list building or property parsing required.
- **Right extension point**: The filter runs directly inside the auto-configuration import pipeline, at the earliest possible moment before any auto-configuration class is loaded or its conditions evaluated.
- **Forward-compatible**: `EnvironmentPostProcessor` is deprecated for removal in Spring Boot 4; `AutoConfigurationImportFilter` is the recommended approach.

## 🚀 Benchmark Results

Startup time benchmarks run automatically on every push to `main` against a [PetClinic](integration-tests/petclinic-sample)-like application (Web + JPA + Actuator + Cache + Validation) using Java 17, 21, and 25 on GitHub Actions runners.

> **[View the latest benchmark report →](../../actions/workflows/benchmarks.yml)**  
> Download the `benchmark-report` artifact from the most recent successful run for exact numbers.

The actual improvement depends on how many Spring Boot starters your application uses. The more auto-configurations Spring Boot has to evaluate at startup, the more you gain.

## Quick Start

### Maven

Declare the core as a runtime dependency, then add the plugin. A Maven plugin cannot contribute a dependency to the project building it, so this one declaration is required - the `train` goal fails with the exact XML if it is missing.

```xml
<dependency>
    <groupId>com.patbaumgartner</groupId>
    <artifactId>autoconfiguration-optimizer-core</artifactId>
    <version>1.0.0</version>
    <scope>runtime</scope>
</dependency>
```

The `train` goal generates the optimizer properties file, and `verify` fails the build if it goes stale:

```xml
<plugin>
    <groupId>com.patbaumgartner</groupId>
    <artifactId>spring-boot-autoconfiguration-optimizer-maven-plugin</artifactId>
    <version>1.0.0</version>
    <executions>
        <execution>
            <goals>
                <goal>train</goal>
                <goal>verify</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <!-- Auto-detected from @SpringBootApplication or start-class property.
             Set explicitly if auto-detection fails. -->
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

Or run the training goal directly on the command line:

```bash
mvn com.patbaumgartner:spring-boot-autoconfiguration-optimizer-maven-plugin:train

# Generates target/classes/META-INF/autoconfiguration-optimizer.properties
```

Re-run training whenever your application's dependencies change significantly.

### Gradle

Apply the plugin. It adds `autoconfiguration-optimizer-core` to your `runtimeOnly` configuration at a matching version, so there is nothing else to declare:

```groovy
plugins {
    id 'com.patbaumgartner.autoconfiguration-optimizer' version '1.0.0'
}

autoconfigurationOptimizer {
    // Auto-detected from @SpringBootApplication.
    // Set explicitly if auto-detection fails.
    mainClass = 'com.example.MyApplication'

    // Optional: extra JVM arguments passed to the training-run process
    jvmArguments = ['-Xmx512m', '-Dspring.profiles.active=training']

    // Optional: timeout in seconds (default: 120)
    timeout = 120
}
```

Run the training step (automatically wired into `jar`/`bootJar`):

```bash
./gradlew bootJar

# The training and copy tasks run automatically before packaging
# Generates build/classes/java/main/META-INF/autoconfiguration-optimizer.properties
```

Re-run training whenever your application's dependencies change significantly.

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
| `mainClass` | `autoconfiguration.optimizer.mainClass` | auto-detected | Fully-qualified main class. Auto-detected from `@SpringBootApplication` scan or `start-class` property when omitted. |
| `jvmArguments` | `autoconfiguration.optimizer.jvmArguments` | _(none)_ | Additional JVM arguments passed to the training-run process. |
| `jar` | `autoconfiguration.optimizer.jar` | _(none)_ | Spring Boot executable JAR to run. When set, `mainClass` is ignored. |
| `timeout` | `autoconfiguration.optimizer.timeout` | `120` | Training run timeout in seconds. |
| `targetDirectory` | `autoconfiguration.optimizer.targetDirectory` | `${project.build.outputDirectory}/META-INF` | Directory where the properties file is copied after training. |
| `outputFile` | `autoconfiguration.optimizer.outputFile` | `autoconfiguration-optimizer.properties` | Name of the generated properties file. |
| `workingDirectory` | `autoconfiguration.optimizer.workingDirectory` | `${project.build.directory}` | Working directory for the training process. |
| `skip` | `autoconfiguration.optimizer.skip` | `false` | Skip the training run. |

### Maven Plugin Parameters (`verify` goal)

| Parameter | Property | Default | Description |
|---|---|---|---|
| `trainingFile` | `autoconfiguration.optimizer.trainingFile` | `${project.build.outputDirectory}/META-INF/autoconfiguration-optimizer.properties` | Training file to check. |
| `failOnMissing` | `autoconfiguration.optimizer.failOnMissing` | `true` | Fail when no training file is present. |
| `failOnStale` | `autoconfiguration.optimizer.failOnStale` | `true` | Fail when the recorded auto-configuration candidates no longer match the project's runtime classpath. |
| `skip` | `autoconfiguration.optimizer.skip` | `false` | Skip the verification. |

### Gradle Plugin Extension (`autoconfigurationOptimizer`)

| Property | Default | Description |
|---|---|---|
| `mainClass` | auto-detected | Fully-qualified main class. Auto-detected from `@SpringBootApplication` scan when omitted. |
| `jvmArguments` | _(none)_ | Additional JVM arguments passed to the training-run process. |
| `jar` | _(none)_ | Spring Boot executable JAR to run. When set, `mainClass` is ignored. |
| `timeout` | `120` | Training run timeout in seconds. |
| `targetDirectory` | `build/classes/java/main/META-INF` | Directory where the properties file is copied after training. |
| `outputFile` | `autoconfiguration-optimizer.properties` | Name of the generated properties file. |
| `skip` | `false` | Skip the training run. |
| `coreVersion` | the plugin's version | Version of `autoconfiguration-optimizer-core` added to `runtimeOnly`. |

## Spring Boot Compatibility

| Spring Boot | Java | Build Tool | Status |
|---|---|---|---|
| 4.1.x | 17, 21, 25 | Maven, Gradle 9 | ✅ Default |

> **Spring Boot 4** requires **Java 17 minimum** and is the default target for this library.

## Running Benchmarks Locally

```bash
# Build everything
mvn package -DskipTests

# Run benchmarks
./benchmarks/scripts/run-benchmarks-maven.sh \
  integration-tests/petclinic-sample/target/autoconfiguration-optimizer-petclinic-sample-*.jar

# View the report
cat benchmarks/results/benchmark-report.md
```

Benchmarks also run automatically in CI on every push to `main` and results are available as [GitHub Actions artifacts](../../actions/workflows/benchmarks.yml).

## Project Structure

```
spring-boot-autoconfiguration-optimizer/
├── autoconfiguration-optimizer-core/          # Core library (filter + training listener)
├── autoconfiguration-optimizer-build-support/ # Shared build-time code used by both plugins
├── spring-boot-autoconfiguration-optimizer-maven-plugin/  # Maven plugin
├── spring-boot-autoconfiguration-optimizer-gradle-plugin/ # Gradle plugin
├── integration-tests/
│   ├── petclinic-sample/                      # Maven integration test app (PetClinic-like)
│   └── petclinic-sample-gradle/               # Gradle integration test app (shares sources with Maven sample)
└── benchmarks/                                # Startup benchmarks + scripts
```

## Contributing

See [CONTRIBUTING.md](.github/CONTRIBUTING.md) to get started and
[CODE_OF_CONDUCT.md](.github/CODE_OF_CONDUCT.md) for community standards.

Formatting is enforced by the build: `spring-javaformat:validate` and `sortpom:verify` run
in the `validate` phase, so `mvn verify` fails on unformatted Java or an unsorted POM. Run
`mvn spring-javaformat:apply` to fix formatting.

## Security

See [SECURITY.md](SECURITY.md) for how to report security vulnerabilities.

## License

Apache License 2.0 - see [LICENSE](LICENSE) for details.
