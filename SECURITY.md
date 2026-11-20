# Security Policy

## Reporting Security Issues

If you discover a potential security vulnerability in WarThunderMapDraw, please report it privately rather than publishing exploit details in a public issue.

You can contact **Krejzonek** through:

- GitHub Private Vulnerability Reporting (if enabled for this repository).
- Direct contact: [your contact email]

Please include a description of the issue, steps to reproduce it, and any relevant technical details.

Please do not publicly disclose vulnerabilities until they have been investigated.

## Security & Validation Controls

The project includes several safeguards intended to reduce misuse and invalid input:

- **Input Limits:** Nicknames are limited to 20 characters. Tactical legend descriptions are limited to 40 characters.
- **Message Handling:** WebRTC DataChannel communication includes message-size, queue, and input validation safeguards.
- **Room Management:** The host can manage session permissions, including drawing access, kicking, and banning participants.
- **Firebase Rules:** Database access is restricted through Firebase Realtime Database Security Rules.
- **Local Game Access:** The application uses the local War Thunder HTTP telemetry interface on port 8111 for map data.

These measures are not a guarantee that the application is completely secure. Users should avoid sharing sensitive information through multiplayer sessions.

## Security Recommendations for Contributors

Contributions must:

- Preserve read-only access to the local War Thunder telemetry interface.
- Validate data received from other participants.
- Respect the project's MIT License.
- Avoid introducing unnecessary permissions or external services.
- Never introduce game memory injection, game file modification, gameplay automation, or anti-cheat circumvention.
- Never commit private keys, credentials, service account files, or other sensitive configuration data.

## Supported Versions

Security fixes are primarily developed for the latest version available in the repository.

Older versions may not receive security updates.
