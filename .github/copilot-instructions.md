# GitHub Copilot Instructions

## Project Overview

**Spring Boot Autoconfiguration Optimizer** is a build-time tool that reduces Spring Boot startup time by eliminating unused auto-configuration evaluation. It works by recording which auto-configurations actually matched during a one-time "training run" and then filtering out all others on every subsequent startup via Spring Boot's `AutoConfigurationImportFilter` extension point.

### How It Works

1. **Training run**: The app starts once with `autoconfiguration.optimizer.training-run=true`. `TrainingRunApplicationListener` reads `ConditionEvaluationReport` and writes the auto-configs that did **not** apply to `META-INF/autoconfiguration-optimizer.properties`.
2. **Depend on the core**: `autoconfiguration-optimizer-core` is an ordinary runtime dependency of the application. The Gradle plugin adds it to `runtimeOnly` automatically; Maven requires an explicit declaration and the `train` goal fails with the exact XML when it is missing.
3. **Production run**: `OptimizedAutoConfigurationImportFilter` reads the properties file and skips exactly the recorded auto-configurations. Anything not recorded is allowed.

---

## Repository Structure

```
spring-boot-autoconfiguration-optimizer/
├── autoconfiguration-optimizer-core/          # Core library (filter, listener, training file format)
├── autoconfiguration-optimizer-build-support/ # Shared build-time code used by both plugins
├── spring-boot-autoconfiguration-optimizer-maven-plugin/  # Maven plugin (train + verify goals)
├── spring-boot-autoconfiguration-optimizer-gradle-plugin/ # Gradle plugin (train + copy tasks)
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
| `TrainingFile` | core | On-disk format contract: keys, format version, candidate digest |
| `TrainMojo` / `TrainTask` | maven / gradle | Forks a training run JVM process |
| `VerifyMojo` | maven | Fails the build when the training file is missing or no longer matches the classpath |
| `MainClassFinder` | build-support | Scans bytecode for `@SpringBootApplication` to auto-detect the main class |
| `AutoConfigurationCandidates` | build-support | Reads candidates off a classpath without starting an app (used by `verify`) |
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

### The training file records exclusions, not inclusions

This is the load-bearing decision. Recording the loaded set made every candidate absent from the file get skipped, so adding a dependency without re-training silently removed its beans from the application. Recording exclusions means an unknown candidate is allowed, so the failure mode is "not optimized yet" rather than "silently broken".

Two consequences follow, and both must be preserved:

- The filter must **not** call `ImportCandidates.load(...)` at runtime. It no longer needs the full candidate set, which is what keeps the classpath from being re-read during startup. Programmatic `@Import`-ed inner configurations (e.g. `DataSourceConfiguration$Hikari`) pass simply because they were never recorded as excluded.
- The file carries `autoconfiguration.optimizer.format-version`. The filter must refuse any version it does not recognise and fall back to allowing everything; a v1 file read as an exclusion list would skip exactly the auto-configurations the application uses.

### Detecting what loaded uses `getUnconditionalClasses()`

An auto-configuration counts as loaded when its class-level conditions fully matched, **or** when it appears in `ConditionEvaluationReport.getUnconditionalClasses()` - the candidates that survived Spring Boot's own import filters without any class-level condition being evaluated. Do not reintroduce the older heuristic of deriving class names from `ClassName#methodName` report keys: it misses unconditional auto-configurations that declare no conditional `@Bean` method.

Candidates rejected by Spring Boot's own filters appear in neither collection and are correctly treated as unused.

### Staleness is checked at build time, not startup

The training file records a SHA-256 digest of the candidate class names. `VerifyMojo` recomputes it from the project's runtime classpath and fails on a mismatch. This deliberately stays out of the startup path. It only detects **classpath** drift - conditions can change outcome without any candidate name changing - so the docs must keep saying that re-training is required after dependency or configuration changes.

### Gradle task annotations (Gradle 9)

Gradle 9 `validatePlugins` requires:
- Every task must be annotated with `@DisableCachingByDefault` or `@CacheableTask`.
- Every `@InputFile` / `@InputFiles` property must also carry `@PathSensitive` / `@Classpath` / `@CompileClasspath`.

### The core is a declared dependency, never injected

Earlier versions copied the core's classes and Spring metadata into the application's build output so no dependency had to be declared. **Do not reintroduce that.** It hid the optimizer from the dependency graph and therefore from SBOM, licence and vulnerability tooling; it left stale classes behind when the plugin was upgraded, because entries were only copied when absent; it copied the core's own `META-INF/maven/**` into the consumer's artifact; and it silently required the core to have no dependencies, since only its classes travelled.

- Gradle: `AutoConfigurationOptimizerPlugin` adds the core to `runtimeOnly`. The version is read from `optimizer.properties`, generated into the plugin JAR by `build.gradle`, so it always matches the plugin. `coreVersion` on the extension overrides it.
- Maven: a plugin cannot contribute a dependency to the project building it without mutating the model, so `TrainMojo` fails with copy-pasteable XML and warns on a version mismatch.

The core is still kept dependency-light by a `maven-enforcer-plugin` rule, because it ships into applications whose Spring Boot version it must not conflict with.

### Gradle plugin task wiring

The chain is `trainAutoconfiguration` -> `copyAutoconfigurationOptimizerFile` -> `jar`/`bootJar`. `bootJar`, `bootWar` (guarded by `project.plugins.withId("war")`) and `resolveMainClassName` all depend on the copy task; Spring Boot's archive tasks do not depend on `jar`, and `resolveMainClassName` scans the directory the copy task writes into, so Gradle fails the build if that ordering is not declared.

`copyAutoconfigurationOptimizerFile` must stay a real `Copy` task. An ad-hoc task with a `doLast` block that touches the `Project` cannot be serialized into the configuration cache, which Gradle 9 enables by default, and made `bootJar` fail outright for every consumer.

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
3. Spring Java Format applied (`mvn spring-javaformat:apply`). `validate` and `sortpom:verify` are bound to the `validate` phase, so an unformatted file or unsorted POM fails the build.
4. Javadoc present for any new public API.
5. PR description includes type of change, testing done, and related issues.
