# WarThunderMapDraw

> **AI Usage & Acknowledgements:** AI tools were used to assist with the creation of the application icon and the visual design of selected user interface elements. The application functionality, architecture, and integration were developed by the project author.

An open-source Android tactical map companion for **War Thunder**.

WarThunderMapDraw reads battlefield information from the game's built-in local HTTP telemetry interface (`port 8111`) and allows players to share tactical maps, markers, and drawings in real time using **WebRTC peer-to-peer connections**.

> **Disclaimer:** WarThunderMapDraw is an unofficial, independent fan-made project developed by **Krejzonek**. It is not affiliated with, endorsed by, sponsored by, or approved by **Gaijin Entertainment**. _War Thunder_ and all related trademarks belong to their respective owners.

## Features

### Live Map Telemetry

- Connects to War Thunder's local HTTP interface on port `8111`.
- Discovers the available local game telemetry endpoint.
- Retrieves:
  - `/map_info.json`
  - `/map_obj.json`
  - `/map.img`
- Displays available battlefield information on an Android device.

### Tactical Drawing and Markers

- Freehand tactical drawing with color selection.
- Tactical legend with descriptions of up to **40 characters**.
- Markers for ground, air, and naval units.
- Capture zones, airfields, and tactical indicators.
- Undo and redo support for drawing layers.
- Map zooming and panning.

### Multiplayer Sessions

- Create or join rooms using codes in the `WT-XXXXXX` format.
- Support for up to **8 participants**.
- Nicknames limited to **20 characters**.
- Real-time synchronization using WebRTC DataChannels.
- Firebase Realtime Database for room state and connection signaling.
- Firebase Anonymous Authentication.
- Host moderation features:
  - Enable or disable drawing permissions.
  - Kick participants.
  - Ban participants.

### Tactical Map Export

- Export the map with tactical drawings and markers.
- Include the tactical legend in the exported PNG image.

## Architecture Overview

| Component                   | Responsibility                                                               |
| --------------------------- | ---------------------------------------------------------------------------- |
| `HomeActivity`              | Username management, session entry, and room validation.                     |
| `WarThunderApiClient`       | Discovers and communicates with the local War Thunder HTTP interface.        |
| `WarThunderMapParser`       | Parses map information, battlefield objects, and coordinates.                |
| `MultiplayerSessionManager` | Firebase authentication, room management, signaling, and WebRTC connections. |
| `MapDrawingManager`         | Manages drawings, markers, and tactical annotations.                         |
| `MapOverlayView`            | Renders the map, markers, and drawing layers.                                |
| `TacticalMapExporter`       | Exports the tactical map and annotations to PNG.                             |

## Network Architecture

The application uses two separate communication mechanisms:

**1. Firebase Realtime Database**

Used for:

- Room management.
- Participant information.
- Session state.
- WebRTC signaling.

**2. WebRTC Peer-to-Peer**

Used for:

- Sharing map images.
- Synchronizing battlefield objects.
- Sharing tactical drawings and annotations.

Map images and live battlefield information are intended to be transmitted directly between participants rather than stored in Firebase Realtime Database.

The host device obtains the game map from its local War Thunder HTTP interface and shares the relevant information with other participants.

## Fair Play and Game Compatibility

WarThunderMapDraw is designed as a tactical visualization and coordination tool.

Its intended operation:

- Uses the local War Thunder HTTP telemetry interface.
- Does not intentionally modify game memory.
- Does not modify War Thunder game files.
- Does not inject code into the game.
- Does not automate gameplay or control vehicles.
- Does not intentionally bypass or interfere with anti-cheat systems.

The developer does not guarantee that the application is permitted under every version of War Thunder's Terms of Service or other applicable game policies. Users are responsible for checking the rules applicable to their accounts.

## Requirements

- Android device.
- War Thunder running on a computer.
- The Android device and the computer running the game connected to the same local network for host functionality.
- War Thunder's local HTTP interface available on port `8111`.
- Internet connection for Firebase and multiplayer connectivity.

Network configuration, firewall settings, and router restrictions may affect connectivity.

## Setup and Building from Source

### 1. Clone the Repository

```bash
git clone https://github.com/Krejzonek/WarThunderMapDraw.git
cd WarThunderMapDraw
```

### 2. Open the Project

Open the project in Android Studio and allow Gradle to synchronize.

### 3. Configure Firebase

To run multiplayer functionality with your own Firebase project:

1. Create a Firebase project.
2. Register an Android application using the package name `com.example.warthundermapdraw`.
3. Download `google-services.json`.
4. Place the file in the application's `app/` directory.
5. Enable Firebase Anonymous Authentication.
6. Create a Firebase Realtime Database instance.
7. Configure the database URL in the application.
8. Deploy the project's Firebase Realtime Database Security Rules.

The application currently uses Firebase Realtime Database in the `europe-west1` region.

Do not use permissive database rules in a public or production deployment.

### 4. Build and Run

Build the application using Android Studio and install it on your Android device.

### 5. Connect to War Thunder

For host functionality:

1. Start War Thunder on your computer.
2. Make sure the local HTTP telemetry interface is available.
3. Connect your Android device to the same local network.
4. Open WarThunderMapDraw.
5. Create a multiplayer session and share the generated room code.

Other participants can join using the room code.

## Firebase Security Rules

The Firebase Realtime Database rules used by the project are maintained separately in:

`firebase/database.rules.json`

These rules are part of the project's security configuration and should be reviewed before deployment.

Never replace them with unrestricted public read or write access.

## Project Documentation

- [Terms of Use](TERMS.md)
- [Privacy Policy](PRIVACY.md)
- [Security Policy](SECURITY.md)
- [Contributing Guidelines](CONTRIBUTING.md)
- [MIT License](LICENSE)

## License

This project is licensed under the **MIT License**.

See the [LICENSE](LICENSE) file for details.

## Author

**Krejzonek**

GitHub: https://github.com/Krejzonek
