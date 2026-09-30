# Antigravity Android Client 🚀

[![Build Android App](https://github.com/dgocker/antigravity-android/actions/workflows/build.yml/badge.svg)](https://github.com/dgocker/antigravity-android/actions/workflows/build.yml)
![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-blue.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3-purple.svg)
![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20Material3-brightgreen.svg)
![Architecture](https://img.shields.io/badge/Architecture-Offline--First%20%7C%20Room%20SSOT-orange.svg)
[![Donate](https://img.shields.io/badge/Donate-DonationAlerts-FF6B00?logo=donationalerts&logoColor=white)](https://www.donationalerts.com/r/dgocker)

A native, high-performance, offline-first Android control panel and IDE-like client for **Antigravity CLI (`agy`)** communicating with your server via `agy-gateway`.

---

## 🌟 Key Features

- **Offline-First Resilience**: Antigravity runs continuously on the server regardless of phone connectivity. If connectivity drops or the app is killed, reconnection automatically replays all missed events via `after=<lastReceivedSeq>` with guaranteed zero duplicates.
- **Voice Notes & Instant Transcription**: Record and send voice messages directly from the composer. Audio is transcribed seamlessly with instant UI updates and integrated player with duration tracking.
- **In-Bubble Live Tool Status**: Real-time status (`Running run_command...`, `Done: ...`, etc.) is embedded directly inside the active agent response bubble above the progress indicator, eliminating distracting jumping cards.
- **Dedicated Spoilers for Tools & File Changes**:
  - `Executed tools`: Collapsible spoiler for bash commands, searches, and reads.
  - `Modified files`: Dedicated collapsible spoiler for all `replace_file_content` and `write_to_file` edits with syntax-highlighted diffs and one-tap copy.
- **Interactive Multi-Choice Questions**: Seamless support for agent `ask_question` tool calls with interactive single and multi-select choices.
- **Rich Media & 500 MB Uploads**: Upload images, screen recordings, logs, and files up to 500 MB. Download and preview generated images, diffs, and deliverables directly inside the app.
- **Live Streaming**: Real-time token streaming (`text_delta`) buffered in memory for low-latency visual feedback without database thrashing.
- **Interactive Controls**:
  - Stop active turns with instant Cancel (`SIGTERM` -> `SIGKILL` or tmux abort).
  - Dynamic model selector loaded from server (`/v1/models`).
  - Reasoning effort control (`low`, `medium`, `high`).
  - Execution mode switch (`plan`, `accept-edits`).
- **File Explorer & Code Viewer**: Drill down through workspace directories, inspect files with line numbers, and quote code snippets directly into the agent composer ("Ask Agent").
- **Device Token Authentication**: Uses device-specific bearer tokens with SHA-256 server-side hash verification. Raw tokens are never logged, and are stored securely encrypted via Android Keystore on the device.

---

## 🏗️ Architecture

```
                    ANDROID CLIENT (v1.0.33)
                              │
                              ▼
                     ┌─────────────────┐
                     │   Compose UI    │
                     └────────┬────────┘
                              │
                         ViewModels
                              │
                              ▼
                     ┌─────────────────┐
                     │   Repositories  │
                     └────────┬────────┘
                              │
                     ┌────────┴─────────┐
                     ▼                  ▼
               ┌───────────┐      ┌─────────────┐
               │  Room DB  │      │ Sync Engine │
               └───────────┘      └──────┬──────┘
                                         │
                                HTTPS / WSS (Nginx 8444)
                                         │
                                         ▼
                                ┌────────────────┐
                                │  agy-gateway   │
                                │    FastAPI     │
                                └───────┬────────┘
                                        │
                                        ▼
                                ┌────────────────┐
                                │ Antigravity    │
                                │   CLI (`agy`)  │
                                └────────────────┘
```

- **Local Source of Truth**: Room Database persists all conversations, runs, steps, events, and file cache. UI observes Room via reactive StateFlow.
- **Sync Engine**: Manages WebSocket lifecycle, parses incoming stream-json events, updates monotonic `seq` state, and handles exponential backoff reconnects (1s, 2s, 4s, 8s, 16s, 30s max).
- **Zero Secrets in Git**: No credentials, private keys, or real tokens are committed to source control.

---

## 📱 App Screens

1. **Connection Screen**: Configure Server URL, Device Token, and Device Name with connectivity verification test.
2. **Chats List Screen**: Displays all active and archived agent sessions, live run status badges, search filtering, and quick creation dialog.
3. **Chat & Agent View**:
   - In-bubble live action status indicator (`Running run_command...`, `Done: ...`) with inline **Stop** button
   - Normalized steps: `USER_INPUT`, `PLANNER_RESPONSE`, `TOOL_CALL`, `GENERIC`, `ERROR`
   - Collapsible `Executed tools` spoiler for terminal and read commands
   - Collapsible `Modified files` spoiler for code diffs and file edits
   - Native voice note player with waveforms and live speech transcriptions
   - Deliverables grid (images, videos, documents, build artifacts)
   - Interactive `ask_question` option buttons
   - Model, reasoning effort, and mode pickers
4. **Workspace File Explorer**: Browse repository files and directories with file size and timestamp metadata.
5. **Code Viewer**: Monospace code viewer with line numbers and **Ask Agent** snippet selection quoting.
6. **Settings Screen**: Inspect active server device tokens, revoke tokens remotely, configure agent defaults, or disconnect.

---

## 🖥️ Server Gateway (`server/`)

This repository contains both the Android client (`app/`) and the companion server gateway (`server/`) that bridges the mobile app with the Antigravity CLI and interactive terminal sessions:

- **Complete Installation & Deployment Guide:** 👉 **[server/README.md](server/README.md)**
- **One-Command Automated Installer:** `server/install.sh`
- **Systemd Unit Template:** `server/agy-gateway.service`
- **API Reference Specification:** `server/API.md`

### 🔐 Provisioning Device Tokens on Server

To connect an Android device to your `server` gateway:

1. SSH into the server running the gateway.
2. Generate a dedicated device token using the CLI tool:
   ```bash
   python3 server/agy-token.py create "Pixel 8 Pro"
   ```
   Output:
   ```text
   Device: Pixel 8 Pro (ID: 1)
   Token:
   agy_android_9f8e7d6c5b4a3210abcdef0123456789
   ```
3. Open the Antigravity Android app, enter your Server URL (`https://your-domain` or `http://127.0.0.1:8765`), and paste the generated token.
4. If a device is lost or compromised, revoke its token anytime on the server:
   ```bash
   python3 server/agy-token.py revoke 1
   ```
   Or directly via the app's Settings screen.

---

## ⚡ Automated Builds via GitHub Actions

Every commit to `main` triggers a complete build and test pipeline in GitHub Actions:
- Compiles Kotlin & Jetpack Compose
- Runs unit tests (`TokenStoreTest`, `EventParsingTest`, `DeduplicationTest`)
- Builds the debug APK
- Publishes the ready-to-install `app-debug.apk` as a workflow artifact.

To download the latest APK:
1. Go to the [Actions tab](https://github.com/dgocker/antigravity-android/actions) in this repository.
2. Click on the latest workflow run.
3. Scroll to the **Artifacts** section at the bottom and download `antigravity-debug-apk.zip`.

---

## 🛠️ Local Build Instructions

### Prerequisites
- JDK 17 or JDK 21
- Android SDK (API 36, build-tools 36.0.0)

### Commands
```bash
# Run unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug
```
The output APK is generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 🌐 Nginx WebSocket Reverse Proxy Example

If hosting `agy-gateway` behind Nginx with SSL:

```nginx
server {
    server_name agy.yourdomain.com;

    location / {
        proxy_pass http://127.0.0.1:8765;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }

    listen 443 ssl;
    # ssl_certificate ...
    # ssl_certificate_key ...
}
```

---

## ☕ Support

If this project helps you or saves you time, consider supporting its development:

[![Donate](https://img.shields.io/badge/Donate-DonationAlerts-FF6B00?style=for-the-badge&logo=donationalerts&logoColor=white)](https://www.donationalerts.com/r/dgocker)

**[donationalerts.com/r/dgocker](https://www.donationalerts.com/r/dgocker)**

