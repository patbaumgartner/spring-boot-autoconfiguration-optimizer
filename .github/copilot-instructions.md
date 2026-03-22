# GitHub Copilot Instructions

## Project Overview

**Spring Boot Autoconfiguration Optimizer** is a build-time tool that reduces Spring Boot startup time by eliminating unused auto-configuration evaluation. It works by recording which auto-configurations actually matched during a one-time "training run" and then filtering out all others on every subsequent startup via Spring Boot's `AutoConfigurationImportFilter` extension point.

### How It Works

1. **Training run**: The app starts once with `autoconfiguration.optimizer.training-run=true`. `TrainingRunApplicationListener` reads `ConditionEvaluationReport` and writes matched auto-configs to `META-INF/autoconfiguration-optimizer.properties`.
2. **Inject**: The Maven/Gradle plugin embeds the optimizer core classes (including the filter and the training file) directly into the Spring Boot fat JAR via `CoreInjector`.
3. **Production run**: `OptimizedAutoConfigurationImportFilter` reads the properties file and restricts Spring Boot's `AutoConfigurationImportSelector` to only import auto-configurations that were in the training set.

---

## Repository Structure

```
spring-boot-autoconfiguration-optimizer/
├── autoconfiguration-optimizer-core/          # Core library (filter, listener, hints)
├── spring-boot-autoconfiguration-optimizer-maven-plugin/  # Maven plugin (train + inject goals)
├── spring-boot-autoconfiguration-optimizer-gradle-plugin/ # Gradle plugin (trainOptimizer + injectOptimizerCore tasks)
├── integration-tests/
│   ├── petclinic-sample/                      # Maven integration test (PetClinic-like app)
│   └── petclinic-sample-gradle/               # Gradle integration test (shares sources)
├── benchmarks/                                # Startup benchmarks + scripts
├── .github/
│   ├── workflows/                             # CI, benchmarks, release, dependency-review
│   └── ISSUE_TEMPLATE/                        # Bug report and feature request templates
└── pom.xml                                    # Maven multi-module parent (excludes integration-tests)
```

### Key Classes

| Class | Module | Purpose |
|---|---|---|
| `OptimizedAutoConfigurationImportFilter` | core | Reads training file, filters auto-config candidates at import time |
| `TrainingRunApplicationListener` | core | Captures matched auto-configs during training run and writes properties file |
| `AutoConfigurationOptimizerProperties` | core | `@ConfigurationProperties(prefix="autoconfiguration.optimizer")` |
| `CoreInjector` | maven + gradle | Finds the core JAR via `ProtectionDomain`, extracts and merges it into the build output |
| `TrainMojo` / `TrainTask` | maven / gradle | Forks a training run JVM process |
| `InjectMojo` / `InjectTask` | maven / gradle | Invokes `CoreInjector` to embed the core into the classes directory |
| `MainClassFinder` | maven + gradle | Scans bytecode for `@SpringBootApplication` to auto-detect the main class |
| `AutoConfigurationOptimizerPlugin` | gradle | Gradle plugin entry point; wires tasks to `bootJar`, `bootWar`, `resolveMainClassName` |

---

## Build & Test Commands

### Maven (root reactor)

```bash
# Full build (skip tests)
mvn --batch-mode --no-transfer-progress install -DskipTests -q

# Build and test everything in the root reactor
mvn --batch-mode --no-transfer-progress verify

# Test only the core module
mvn test -pl autoconfiguration-optimizer-core

# Run petclinic integration tests (NOT in root reactor – must be run separately)
mvn install -DskipTests -q -pl spring-boot-autoconfiguration-optimizer-maven-plugin -am
mvn verify -f integration-tests/petclinic-sample/pom.xml

# Apply Spring Java Format to a module
mvn io.spring.javaformat:spring-javaformat-maven-plugin:apply -pl autoconfiguration-optimizer-core
```

### Gradle plugin

```bash
# First install parent + core to mavenLocal so the Gradle build can find them
mvn install -DskipTests -q -N
mvn install -DskipTests -q -pl autoconfiguration-optimizer-core

# Then build/test the Gradle plugin
cd spring-boot-autoconfiguration-optimizer-gradle-plugin
./gradlew --no-daemon build
./gradlew --no-daemon test
```

---

## Code Style & Conventions

- **Java 17** minimum (`maven.compiler.release=17`, Gradle `VERSION_17`). CI matrix: Java 17, 21, 25.
- **Spring Boot 4.0.3** (GA). Spring Boot 4 requires Java 17+.
- Java sources are formatted with **spring-javaformat** (`mvn io.spring.javaformat:spring-javaformat-maven-plugin:apply`). Always apply before committing.
- POMs are sorted with **sortpom** (`mvn com.github.ekryd.sortpom:sortpom-maven-plugin:sort -Dsort.predefinedSortOrder=custom_1`).
- Follow standard Java conventions: 4-space indentation, Javadoc for public APIs, focused methods.
- Do **not** add comments unless they match the style of existing comments or explain a genuinely complex decision.
- Use existing libraries. Only add new dependencies if absolutely necessary.

---

## Key Design Decisions

### `AutoConfigurationImportFilter` (not `EnvironmentPostProcessor`)

The optimizer uses `AutoConfigurationImportFilter` registered in `META-INF/spring.factories` (key: `org.springframework.boot.autoconfigure.AutoConfigurationImportFilter`). Do **not** switch to `EnvironmentPostProcessor` – it is deprecated for removal in Spring Boot 4 and is less efficient (it would cause a second pass over candidates).

### `spring.factories` must be kept

`META-INF/spring.factories` cannot be removed. Spring Boot 4's `AutoConfigurationImportSelector.getAutoConfigurationImportFilters()` uses `SpringFactoriesLoader.loadFactories()` to discover `AutoConfigurationImportFilter` implementations. There is no alternative registration mechanism for this interface.

### Filter must distinguish registered candidates from programmatic imports

In Spring Boot 4, `AutoConfigurationImportFilter.match()` is called not only for top-level `AutoConfiguration.imports` candidates but also for programmatic `@Import`-ed inner configurations (e.g., `DataSourceConfiguration$Hikari`). The filter must use `ImportCandidates.load(AutoConfiguration.class, classLoader)` to build a set of registered candidates and **only filter entries that appear in that set**. Inner configurations not in the set must always be allowed through (return `true`).

### Training run detection covers both class-level and method-level keys

`ConditionEvaluationReport` entries can appear as either `ClassName` keys (class-level conditions) or `ClassName#methodName` keys (method-level conditions, e.g., `@Bean` methods). `TrainingRunApplicationListener.detectLoadedAutoConfigurations()` must check both forms to capture auto-configs like `EndpointAutoConfiguration` that only have method-level conditions.

### Gradle task annotations (Gradle 9)

Gradle 9 `validatePlugins` requires:
- Every task must be annotated with `@DisableCachingByDefault` or `@CacheableTask`.
- Every `@InputFile` / `@InputFiles` property must also carry `@PathSensitive` / `@Classpath` / `@CompileClasspath`.

### Core injection mechanism

`CoreInjector` locates the core JAR via `OptimizedAutoConfigurationImportFilter.class.getProtectionDomain().getCodeSource().getLocation()`, then extracts all `.class` files and resource files into the project build output directory, carefully merging `spring.factories` and `AutoConfiguration.imports` rather than overwriting them.

### Gradle plugin task wiring

`AutoConfigurationOptimizerPlugin` wires three Spring Boot tasks to depend on `injectOptimizerCore`: `bootJar`, `bootWar` (guarded by `project.plugins.withId("war")`), and `resolveMainClassName`. The `injectOptimizerCore` task in turn depends on `trainOptimizer`, which in turn depends on `jar`.

---

## Configuration Properties Reference

### Core (`autoconfiguration.optimizer.*`)

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | Enable/disable the optimizer at runtime |
| `training-run` | `false` | Enable training mode |
| `output-file` | `autoconfiguration-optimizer.properties` | Output file name |
| `output-directory` | `.` | Output directory for training |
| `exit-after-training` | `false` | Exit JVM after training |

### Maven plugin (`train` goal parameters)

| Parameter | Property | Default | Description |
|---|---|---|---|
| `mainClass` | `autoconfiguration.optimizer.mainClass` | auto-detected | Fully-qualified main class |
| `jvmArguments` | `autoconfiguration.optimizer.jvmArguments` | _(none)_ | Extra JVM args for training process |
| `jar` | `autoconfiguration.optimizer.jar` | _(none)_ | Boot executable JAR (overrides mainClass) |
| `timeout` | `autoconfiguration.optimizer.timeout` | `120` | Training timeout in seconds |
| `targetDirectory` | `autoconfiguration.optimizer.targetDirectory` | `${project.build.outputDirectory}/META-INF` | Where to copy the training file |
| `outputFile` | `autoconfiguration.optimizer.outputFile` | `autoconfiguration-optimizer.properties` | Generated properties file name |
| `workingDirectory` | `autoconfiguration.optimizer.workingDirectory` | `${project.build.directory}` | Working dir for training process |
| `skip` | `autoconfiguration.optimizer.skip` | `false` | Skip the training run |

### Gradle plugin extension (`autoconfigurationOptimizer { }`)

| Property | Default | Description |
|---|---|---|
| `mainClass` | auto-detected | Fully-qualified main class |
| `jvmArguments` | _(none)_ | Extra JVM args for training process |
| `jar` | _(none)_ | Boot executable JAR (overrides mainClass) |
| `timeout` | `120` | Training timeout in seconds |
| `targetDirectory` | `build/classes/java/main/META-INF` | Where to copy the training file |
| `outputFile` | `autoconfiguration-optimizer.properties` | Generated properties file name |
| `skip` | `false` | Skip the training run |

---

## Testing Conventions

- Unit tests live under `src/test/java` alongside their production classes and follow standard JUnit 5 + AssertJ conventions.
- Integration tests for the Maven plugin live in `integration-tests/petclinic-sample/` and are **not** part of the root reactor; they must be run separately (see build commands above).
- Do not remove or weaken existing tests. If a change requires modifying a test, update it to match the new behaviour while keeping coverage equivalent.

---

## Benchmarks

Benchmarks run automatically on push to `main` via `.github/workflows/benchmarks.yml`. To run locally:

```bash
mvn package -DskipTests
./benchmarks/scripts/run-benchmarks-maven.sh \
  integration-tests/petclinic-sample/target/autoconfiguration-optimizer-petclinic-sample-*.jar
cat benchmarks/results/benchmark-report.md
```

---

## Contributing Checklist

Before opening a PR, ensure:

1. `mvn verify` passes for all Maven modules in the root reactor.
2. Gradle plugin tests pass (`./gradlew --no-daemon test` from `spring-boot-autoconfiguration-optimizer-gradle-plugin/`).
3. Spring Java Format applied (`mvn io.spring.javaformat:spring-javaformat-maven-plugin:apply`).
4. Javadoc present for any new public API.
5. PR description includes type of change, testing done, and related issues.
