# Contributing to Spring Boot Autoconfiguration Optimizer

Thank you for your interest in contributing! Here's how to get started.

## Development Setup

1. **Prerequisites**
   - Java 17+
   - Maven 3.9+
   - Gradle 8.x (for the Gradle plugin)

2. **Clone the repository**
   ```bash
   git clone https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer.git
   cd spring-boot-autoconfiguration-optimizer
   ```

3. **Build Maven modules**
   ```bash
   mvn --batch-mode --no-transfer-progress verify
   ```

4. **Build the Gradle plugin**

   The Gradle plugin resolves the core and build-support artifacts from your local Maven
   repository, so install them first:
   ```bash
   mvn install -DskipTests -N
   mvn install -DskipTests -pl autoconfiguration-optimizer-core,autoconfiguration-optimizer-build-support
   cd spring-boot-autoconfiguration-optimizer-gradle-plugin
   ./gradlew build
   ```

## Running Tests

- **Maven tests**: `mvn test`
- **Gradle plugin tests**: `cd spring-boot-autoconfiguration-optimizer-gradle-plugin && ./gradlew test`
- **End-to-end**: the sample applications under `integration-tests/` are not part of the root
  reactor and must be run separately. They exercise the full train → package cycle and
  then drive the packaged application over HTTP:
  ```bash
  mvn install -DskipTests -pl spring-boot-autoconfiguration-optimizer-maven-plugin -am
  mvn verify -f integration-tests/petclinic-sample/pom.xml
  ```

## Pull Request Process

1. Fork the repository and create a feature branch
2. Make your changes with clear commit messages
3. Ensure all tests pass
4. Open a pull request targeting the `main` branch
5. Fill in the PR template describing your changes

## Code Style

Formatting is enforced by the build, not by review: `spring-javaformat:validate` and
`sortpom:verify` are bound to the `validate` phase, so `mvn verify` fails on an unformatted
source file or an unsorted POM.

```bash
mvn spring-javaformat:apply
mvn sortpom:sort
```

- Add Javadoc for public APIs
- Keep methods focused and concise

## Reporting Issues

Please use the [GitHub Issues](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/issues) tracker.
