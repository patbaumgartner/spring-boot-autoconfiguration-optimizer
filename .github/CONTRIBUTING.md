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
   ```bash
   cd spring-boot-autoconfiguration-optimizer-gradle-plugin
   ./gradlew build
   ```

## Running Tests

- **Maven tests**: `mvn test`
- **Gradle plugin tests**: `cd spring-boot-autoconfiguration-optimizer-gradle-plugin && ./gradlew test`

## Pull Request Process

1. Fork the repository and create a feature branch
2. Make your changes with clear commit messages
3. Ensure all tests pass
4. Open a pull request targeting the `main` branch
5. Fill in the PR template describing your changes

## Code Style

- Follow standard Java conventions
- Use 4-space indentation
- Add Javadoc for public APIs
- Keep methods focused and concise

## Reporting Issues

Please use the [GitHub Issues](https://github.com/patbaumgartner/spring-boot-autoconfiguration-optimizer/issues) tracker.
