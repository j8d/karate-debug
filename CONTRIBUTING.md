# Contributing to Karate Debug

Thank you for your interest in contributing to Karate Debug! This document provides guidelines for contributing to the project.

## Code of Conduct

By participating in this project, you agree to maintain a respectful and inclusive environment for all contributors.

## How Can I Contribute?

### Reporting Bugs

Before creating bug reports, please check existing issues to avoid duplicates. When creating a bug report, include:

- **Clear title and description**
- **Steps to reproduce** the issue
- **Expected behavior** vs **actual behavior**
- **Environment details** (IDE version, OS, Java version, Karate version)
- **Debug logs** if applicable
- **Screenshots** if relevant

### Suggesting Enhancements

Enhancement suggestions are welcome! Please provide:

- **Clear use case** for the enhancement
- **Detailed description** of the proposed functionality
- **Examples** of how it would work
- **Alternative approaches** you've considered

### Pull Requests

1. **Fork the repository** and create your branch from `main`
2. **Follow existing code style** and conventions
3. **Add tests** for new functionality
4. **Update documentation** as needed
5. **Ensure all tests pass** before submitting
6. **Write clear commit messages**
7. **Reference related issues** in your PR description

## Development Setup

### Prerequisites

- Node.js 20+
- Java 21+
- Maven 3.6+
- VS Code (for VS Code extension development)
- IntelliJ IDEA (for IntelliJ plugin development)

### Building the Project

```bash
# Build shared debug server
cd shared/debug-server && mvn clean package -q

# Copy JAR to both extensions
cp shared/debug-server/target/karate-debug-server-1.0.0.jar vscode/resources/
cp shared/debug-server/target/karate-debug-server-1.0.0.jar intellij/src/main/resources/

# Build VS Code extension
cd vscode && npm install && npm run compile && npm run package

# Build IntelliJ plugin
cd intellij && ./gradlew buildPlugin
```

### Testing

#### VS Code Extension
```bash
cd vscode
npm run test
# Or press F5 in VS Code to launch Extension Development Host
```

#### IntelliJ Plugin
```bash
cd intellij
./gradlew runIde  # Launch sandbox IDE
./gradlew test    # Run tests
```

#### Integration Tests
Use the `test-fixtures` directory with sample Karate projects for testing.

## Project Structure

```
karate-debug/
├── shared/debug-server/     # Java DAP server (shared by both IDEs)
├── vscode/                  # VS Code extension (TypeScript)
├── intellij/                # IntelliJ plugin (Java/Kotlin Gradle)
├── test-fixtures/           # Sample Karate projects for testing
└── .github/workflows/       # CI/CD workflows
```

## Coding Standards

### TypeScript (VS Code)
- Use ESLint and Prettier configurations in the project
- Follow async/await patterns for asynchronous code
- Add JSDoc comments for public APIs

### Java (IntelliJ & Debug Server)
- Follow standard Java conventions
- Use meaningful variable and method names
- Add Javadoc for public methods and classes
- Keep methods focused and concise

## Commit Message Guidelines

- Use present tense ("Add feature" not "Added feature")
- Use imperative mood ("Move cursor to..." not "Moves cursor to...")
- Limit first line to 72 characters
- Reference issues and pull requests after the first line

Examples:
```
Add support for conditional breakpoints

Implement conditional breakpoint functionality in both VS Code
and IntelliJ extensions. Fixes #123.
```

## Release Process

Releases are managed by maintainers via GitHub Actions. Contributors don't need to worry about versioning.

## Questions?

Feel free to open an issue with your question or reach out to the maintainers.

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
