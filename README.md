# LAN Circle

LAN Circle is a lightweight, serverless LAN messenger for local networks. The repository includes a Java desktop app for
Windows, macOS and Debian/Linux plus a native Android client that uses the same LAN protocol.

## Features

- Serverless peer discovery over UDP broadcast.
- Automatic LAN IP detection and username prompt on launch.
- User list with discovered names, LAN IP addresses, status and last-seen time.
- Multi-select recipients for one-to-one or group messaging.
- TCP text delivery for real-time LAN chat.
- Drag-and-drop file and folder transfers.
- Inline screenshot capture and sharing.
- Online, away and busy status broadcasting.
- Transcript search with fast in-memory highlighting.
- Message and transfer logging under `~/LANCircle Logs`.
- Received files are saved under `~/LANCircle Downloads`.

## Run

Build with the JDK:

```bash
javac -d target/classes $(find src/main/java -name '*.java')
jar --create --file target/lan-circle.jar --main-class com.lancircle.Main -C target/classes .
```

Run:

```bash
java -jar target/lan-circle.jar
```

For two-machine testing, run the JAR on two devices connected to the same LAN. Allow Java through the OS firewall for
UDP `45678` and TCP `45679-45680`.

Peers on the same LAN can discover each other and exchange messages or files without a shared key.

## Platform Notes

The desktop Java/Swing client runs on Windows, macOS and Debian/Linux with a JDK. The Android client lives in
`android-app/` and reuses the same discovery, message and file-transfer protocol:

- UDP discovery: `45678`
- TCP messages: `45679`
- TCP file transfer: `45680`

The shared wire format is intentionally dependency-free JSON so an Android client can interoperate without a server.

## Android

Open the repository root in Android Studio and run the `android-app` module. The Android client supports:

- automatic LAN IP detection and username prompt
- peer discovery over Wi-Fi/LAN
- multi-select group messaging
- file picker transfers
- folder picker transfers through Android's document tree picker
- camera image capture and sharing
- away/busy/online status broadcasting
- transcript search and local message logging

The Android app needs Wi-Fi/LAN access. On recent Android versions, grant the nearby Wi-Fi devices permission when
prompted.
