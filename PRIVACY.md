# Privacy Policy

**Last updated: November 20, 2026**

This Privacy Policy explains how War Thunder Tactical Map (the "Application") processes information when you use its features.

The Application is an independent third-party project and is not affiliated with Gaijin Entertainment.

## 1. Data Controller

**Developer:** Krejzonek  
**Contact:** [your contact email]

For privacy-related questions or requests concerning personal data, please use the contact address above.

## 2. Information We Process

The Application may process the following categories of information.

### 2.1 Authentication Information

The Application uses Firebase Anonymous Authentication.

When you use multiplayer functionality, Firebase generates an anonymous user identifier (UID).

This identifier is used to distinguish users and manage multiplayer sessions.

The Application does not require you to register using an email address, password, or social media account.

### 2.2 Nickname

You may choose a nickname to identify yourself to other participants.

The nickname is stored locally on your device and may be shared with other participants during multiplayer sessions.

Please do not use personal or sensitive information as your nickname.

### 2.3 Multiplayer Session Information

When creating or joining a multiplayer room, the Application may process information such as:

- Room identifiers.
- Host and participant identifiers.
- Participant nicknames.
- Session status.
- Connection and signaling information.
- Information required to manage room membership.

This information is processed through Firebase Realtime Database.

### 2.4 Map and Tactical Information

During multiplayer sessions, the Application may transmit information such as:

- Map data obtained from the local War Thunder HTTP API.
- Player and vehicle positions.
- Available battlefield objects.
- Tactical drawings and annotations.

This information is transmitted directly between participants using WebRTC peer-to-peer connections where possible.

Firebase Realtime Database is used for session management and signaling, not as the intended storage location for map images or live gameplay telemetry.

### 2.5 Network Information

Multiplayer connections may involve the processing of network information, including IP addresses and connection metadata.

WebRTC may exchange ICE candidates between participants. Depending on the network configuration, these candidates may reveal IP address information to other participants.

STUN servers may also receive your public IP address and connection-related information when establishing peer-to-peer connections.

## 3. Local War Thunder Data

The Application connects to the local War Thunder HTTP API, typically available on port `8111`.

The Application reads information made available by the game, such as map and battlefield data.

This information is used to display the tactical map and support multiplayer functionality.

The Application does not intentionally modify War Thunder game files or memory.

## 4. Third-Party Services

The Application relies on third-party services to provide certain features.

### 4.1 Firebase

Firebase services provided by Google are used for:

- Anonymous Authentication.
- Multiplayer room management.
- Session signaling and connection coordination.

Firebase may process authentication identifiers, connection metadata, and other information necessary to provide these services.

Google Privacy Policy:

https://policies.google.com/privacy

Firebase Privacy Information:

https://firebase.google.com/support/privacy

### 4.2 Google STUN

The Application may use Google's STUN servers to help establish WebRTC peer-to-peer connections.

Google Privacy Policy:

https://policies.google.com/privacy

### 4.3 Cloudflare STUN

The Application may use Cloudflare's STUN service as an additional connection option.

Cloudflare Privacy Policy:

https://www.cloudflare.com/privacypolicy/

Third-party services operate under their own privacy policies and terms.

## 5. How We Use Information

Information processed by the Application is used for the following purposes:

- Identifying participants in multiplayer sessions.
- Creating and managing multiplayer rooms.
- Establishing peer-to-peer connections.
- Sharing map information and tactical annotations.
- Maintaining session status and coordinating participants.
- Diagnosing connection and functionality issues where applicable.

The Application is not intended to collect information for advertising or user profiling.

## 6. Data Storage and Retention

Different types of information may be stored in different places.

- **Nicknames:** stored locally on the user's device.
- **Authentication identifiers:** managed by Firebase Authentication.
- **Room and session information:** processed and temporarily stored in Firebase Realtime Database as required for multiplayer functionality.
- **Map data, battlefield information and drawings:** transmitted between participants through WebRTC during multiplayer sessions.

The retention period for information stored by Firebase depends on the Application's implementation, session cleanup mechanisms, and Firebase configuration.

The developer does not claim that all information is immediately deleted when a participant disconnects or closes the Application.

## 7. Data Sharing

The Application does not operate its own advertising or analytics platform.

Information may be shared with:

- Other participants in the same multiplayer session.
- Google Firebase, to provide authentication and session management.
- Google and Cloudflare STUN services, to support peer-to-peer connections.

Information shared directly with other participants may be visible to them for the duration of the connection or session.

The developer does not sell personal data.

## 8. International Data Transfers

Third-party providers may process information on servers located outside your country or the European Economic Area (EEA).

Such processing is governed by the providers' privacy policies and applicable data protection safeguards.

For more information, consult the privacy documentation of Google and Cloudflare.

## 9. Data Security

The Application uses third-party infrastructure and network communication mechanisms to provide its features.

Reasonable technical measures are used within the Application's implementation to support secure communication and session management.

However, no internet transmission or storage system can be guaranteed to be completely secure.

Users should avoid sharing sensitive or confidential information through multiplayer features.

## 10. Your Privacy Rights

If you are located in the European Economic Area, you may have rights under the General Data Protection Regulation (GDPR), including:

- The right to access your personal data.
- The right to request correction of inaccurate data.
- The right to request erasure of personal data.
- The right to request restriction of processing.
- The right to object to certain processing.
- The right to data portability, where applicable.
- The right to withdraw consent where processing is based on consent.
- The right to lodge a complaint with a data protection authority.

In Poland, the relevant supervisory authority is the President of the Personal Data Protection Office (UODO).

Website:

https://uodo.gov.pl/

To exercise your rights regarding processing carried out by the Application's developer, contact:

**[your contact email]**

Some information may also be processed independently by third-party service providers. Requests concerning their processing may need to be directed to those providers.

## 11. Children's Privacy

The Application is not specifically designed to collect personal information from children.

If you believe that a child has provided personal information through the Application, please contact the developer.

## 12. Changes to This Privacy Policy

This Privacy Policy may be updated to reflect changes to the Application, its functionality, or applicable legal requirements.

The latest version will be published in the Application's official repository.

## 13. Contact

For questions or requests regarding this Privacy Policy, contact:

**Developer:** Krejzonek  
**Contact:** jakubciolek16@gmail.com
