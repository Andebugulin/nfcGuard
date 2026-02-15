# Privacy Policy — Guardian

**Last updated:** February 2026

## Overview

Guardian is a digital wellbeing app that blocks access to distracting applications on your Android device. This policy explains what data Guardian accesses and how it is handled.

## Data Collection

**Guardian does not collect, transmit, or share any personal data.**

All data stays on your device. There are no servers, no analytics, no tracking, and no network requests made by Guardian.

## Data Stored on Device

Guardian stores the following data locally on your device using Android SharedPreferences:

- **Mode configurations** — names, selected apps, block mode type
- **Schedule configurations** — names, days, times, linked modes
- **NFC tag identifiers** — hardware IDs of registered NFC tags
- **Runtime state** — which modes and schedules are currently active

This data never leaves your device unless you explicitly export it using the Export Config feature.

## Permissions

Guardian requests the following Android permissions:

| Permission | Purpose |
|---|---|
| **Usage Access** | Detect which app is currently in the foreground to determine if it should be blocked |
| **Display Over Apps** | Show a full-screen overlay when a blocked app is opened |
| **Battery Optimization Exemption** | Ensure the blocking service runs reliably in the background |
| **NFC** | Read NFC tag hardware IDs for mode activation/deactivation |
| **Foreground Service** | Keep the app blocking service running persistently |
| **Boot Completed** | Restart the blocking service and reschedule alarms after device reboot |
| **Exact Alarm** | Fire schedule start/end events at precise times |

Guardian does **not** request internet access for its own functionality. The `INTERNET` permission in the manifest is inherited from dependencies and is not actively used by the app.

## Third-Party Services

Guardian does not integrate any third-party services, SDKs, analytics platforms, or advertising networks.

## Data Export

When you use the Export Config feature, a JSON or YAML file is created and saved to a location you choose on your device. This file contains your mode, schedule, and NFC tag configurations. Guardian does not upload this file anywhere.

## Children's Privacy

Guardian does not knowingly collect any data from children. The app does not collect data from anyone.

## Changes to This Policy

If this policy is updated, the changes will be reflected in this document with an updated date.

## Contact

If you have questions about this privacy policy, please open an issue on the project's GitHub repository.
