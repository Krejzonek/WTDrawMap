# Contributing Guidelines

Thank you for your interest in contributing to WarThunderMapDraw!

Contributions, bug reports, and suggestions are welcome.

## Before Contributing

Before submitting a contribution:

1. Read the project's [Terms of Use](TERMS.md).
2. Review the [Security Policy](SECURITY.md).
3. Check the existing issues and pull requests to avoid duplicate work.
4. Make sure your changes are compatible with the project's MIT License.

## Development Guidelines

Please follow these guidelines when modifying the application:

- Preserve the existing Kotlin and Android project structure where possible.
- Keep the application compatible with its supported Android configuration.
- Validate data received from Firebase and other participants.
- Preserve read-only access to War Thunder's local HTTP telemetry API on port 8111.
- Avoid unnecessary permissions, dependencies, and external services.
- Keep user-facing application text in English.
- Do not introduce copyrighted War Thunder game assets without appropriate permission.

## Fair Play Restrictions

Contributions must not introduce:

- Game memory reading or injection.
- Modification of War Thunder game files.
- Gameplay automation or control.
- Anti-cheat circumvention.
- Unauthorized access to game data.

The application is intended for tactical visualization and coordination only.

## Pull Requests

When submitting a pull request:

1. Provide a clear description of the changes.
2. Explain the reason for the changes.
3. Include steps to reproduce and verify bug fixes.
4. Mention any changes to permissions, Firebase access, or network communication.
5. Include screenshots when they help explain UI changes.

Keep pull requests focused on a single feature or issue whenever possible.

## Bug Reports

When reporting a bug, include:

- Android version.
- Application version or commit.
- Steps to reproduce the issue.
- Expected behavior.
- Actual behavior.
- Relevant logs or screenshots, if available.

Do not include passwords, private keys, authentication tokens, or other sensitive information in public issues.

## Security Issues

Do not report exploitable security vulnerabilities through public issues.

Follow the instructions in [SECURITY.md](SECURITY.md) to report them privately.

## License

By submitting a contribution, you agree that your contribution will be distributed under the project's MIT License.
