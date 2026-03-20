# Contributing to Spring Boot Autoconfiguration Optimizer

Thank you for your interest in contributing! Here's how to get started.

## Development Setup

1. **Prerequisites**
   - Java 17+
   - Maven 3.9+
   - Gradle 9.x (only needed to work on the Gradle plugin)

2. **Clone the repository**
   ```bash
   git clone https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer.git
   cd spring-boot-autoconfiguration-optimizer
   ```

3. **Build all Maven modules (core + Maven plugin + benchmarks)**
   ```bash
   mvn --batch-mode --no-transfer-progress verify
   ```

4. **Build the Gradle plugin**

   Install the parent POM and core JAR to the local Maven repository first, then build:
   ```bash
   mvn install -DskipTests -q -N
   mvn install -DskipTests -q -pl autoconfiguration-optimizer-core
   cd spring-boot-autoconfiguration-optimizer-gradle-plugin
   ./gradlew --no-daemon build
   ```

## Running Tests

- **Core and Maven plugin tests**: `mvn test`
- **Gradle plugin tests**: `cd spring-boot-autoconfiguration-optimizer-gradle-plugin && ./gradlew --no-daemon test`

## Running Integration Tests

Integration tests are kept outside the root Maven reactor and must be run separately:

```bash
# Maven integration tests (PetClinic sample)
mvn install -DskipTests -q -pl spring-boot-autoconfiguration-optimizer-maven-plugin -am
mvn verify -f integration-tests/petclinic-sample/pom.xml

# Gradle integration tests (PetClinic sample — runs a full bootJar build)
cd spring-boot-autoconfiguration-optimizer-gradle-plugin && ./gradlew --no-daemon publishToMavenLocal
cd integration-tests/petclinic-sample-gradle && ./gradlew --no-daemon bootJar
```

## Code Style

- Follow standard Java conventions with 4-space indentation and Javadoc for public APIs.
- Java sources are formatted with **Spring Java Format**. Run before committing:
  ```bash
  mvn io.spring.javaformat:spring-javaformat-maven-plugin:apply
  ```
- POM files are sorted with **sortpom**. Run before committing:
  ```bash
  mvn com.github.ekryd.sortpom:sortpom-maven-plugin:sort -Dsort.predefinedSortOrder=custom_1
  ```
- Keep methods focused and concise.

## Pull Request Process

1. Fork the repository and create a feature branch.
2. Apply code formatting (see above) before committing.
3. Ensure all tests pass (Maven + Gradle plugin).
4. Update `CHANGELOG.md` under the `[Unreleased]` section for significant changes.
5. Open a pull request targeting the `main` branch and fill in the PR template.

## Reporting Issues

Please use the [GitHub Issues](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/issues) tracker.
