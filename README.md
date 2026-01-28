# Guardian

<div align="center">
  <img src="app/src/main/ic_launcher-playstore.png" alt="Guardian Logo" style="width: 20%">
</div>

A focused Android application for managing app access through scheduled modes and NFC-based controls.

## Idea

Idea is simple, create mode -> select apps to be blocked/allowed -> set schedule for this mode (optional (example: morning focus time)) -> add NFC tag linked to this mode (optional (example: physical tag on your desk)) -> activate mode manually/by schedule -> put NFC tag, if linked away -> stay focused -> grab nfc tag when you need to use blocked apps.

If no NFC tag is linked to the mode, you can deactivate it using any NFC tag (smart watch,
most headphones, etc...)

Main core of idea: you are not able to unlock mode without physical interaction using NFC tag.

## Overview

Guardian allows you to define blocking modes that restrict access to selected applications. Modes can be activated manually, through time-based schedules, or deactivated using NFC tags. The app runs as a persistent foreground service to ensure reliable blocking even when the device is locked or the app is closed.

## Core Functionality

### Modes

Create blocking modes with two operational patterns:

- **Block Selected**: Blocks access to specified applications
- **Allow Only**: Blocks all applications except those specified

Each mode can optionally require a specific NFC tag for deactivation.

### Schedules

Configure time-based activation of modes with:

- Per-day time configuration (start/end times can vary by day)
- Optional automatic deactivation at end time
- Multiple mode linking per schedule

### NFC Integration

- Register NFC tags for mode-specific unlocking
- Tap any registered tag to deactivate active modes
- Optional: Require specific tags for individual modes

## Technical Requirements

- Android 6.0 (API 23) or higher
- NFC hardware (for NFC unlock functionality)
- Permissions required:
  - Usage Access (to detect foreground apps)
  - Display Over Apps (to show block screen)
  - Battery Optimization exemption (for reliable background operation)

## Architecture

Built with:

- Kotlin
- Jetpack Compose for UI
- Kotlin Coroutines for background operations
- Kotlinx Serialization for state persistence
- AlarmManager for precise schedule execution

The app uses a foreground service pattern to maintain blocking functionality across system events including device reboots and task removal.

## Installation

1. Clone the repository
2. Open in Android Studio
3. Build and run on device (emulator NFC support is limited)

## Configuration Notes

### MIUI and Custom ROMs

Some manufacturers aggressively kill background services. After installation:

1. Open system Settings → Apps → Guardian
2. Disable "Pause app activity if unused"
3. Enable "Autostart" if available
4. Set battery optimization to "No restrictions"

### Persistent Storage

App state is stored in SharedPreferences. Schedule disablement flags are date-stamped and persist only for the current day.

## License

This project has MIT License.

## Contributing

Contributions are welcome!

## Made with ❤️

Claude Ai was used during the development of this project for writing code snippets and styling.
