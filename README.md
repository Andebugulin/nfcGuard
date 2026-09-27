# nfcGuard

<div align="center">
  <img src="app/src/main/ic_launcher-playstore.png" alt="nfcGuard" width="120">
  <p><strong>Block distracting apps behind a physical NFC tag.</strong></p>
  <p>
    <a href="https://play.google.com/store/apps/details?id=com.andebugulin.nfcguard">Google Play</a> ·
    <a href="https://github.com/Andebugulin/nfcGuard/releases/">Releases</a> ·
    <a href="https://andebugulin.github.io/nfcGuard/">Website</a>
  </p>
</div>

Put the tag somewhere inconvenient — the kitchen, a gym bag, a friend's flat — and opening Instagram costs a walk instead of a thumb.

<p align="center">
  <img src="./assets/nfcGuard_home_screen.png" width="220" alt="Home"/>
  <img src="./assets/nfcGuard_modes_screen.png" width="220" alt="Modes"/>
  <img src="./assets/nfcGuard_app_with_overlay.png" width="220" alt="Blocked"/>
</p>

## How it works

Create a **mode** — a set of apps, blocked or allowed. Activate it by hand, or link a **schedule** so it turns itself on. While it's active those apps won't open. Tap a linked **NFC tag** to unlock.

Each tag decides how much it gives you: permanent unlock, or a few minutes before the mode snaps back on. One tag at home for full access, one at the office worth five minutes.

A mode with no tag linked accepts any NFC object — a transit card, your headphones. A mode with tags linked accepts only those.

**Lost your tag?** The delete icon on Home starts a recovery flow: a timed attention challenge, then you pick which tags are gone. Modes deactivate, tags are removed, your configuration survives.

## Requirements

- Android 8.0+ (API 26), NFC hardware
- **Usage Access** — see which app is in front
- **Display over apps** — draw the block screen
- **Battery** — don't get dozed off
- **Accessibility** — required on Pixel and Samsung, faster everywhere else
- **Autostart** — on Xiaomi, Oppo, vivo, Huawei and OnePlus, or blocking stops after a reboot
- **"Pause app if unused"** — turn it off in app settings

The in-app permissions page links to every one of these and re-checks on return.

## Install

From [Google Play](https://play.google.com/store/apps/details?id=com.andebugulin.nfcguard), or grab an APK from [Releases](https://github.com/Andebugulin/nfcGuard/releases/).

## Build

```bash
./gradlew :app:assembleDebug   # APK → app/build/outputs/apk/debug/
./gradlew :app:installDebug    # onto an attached device
./gradlew test                 # 333 unit tests, no emulator
```

NFC needs real hardware; the emulator is unusable for it.

## Architecture

Two Gradle modules. `:domain` is pure Kotlin — `AppState` plus four logic objects (`NfcUnlockLogic`, `ScheduleTransitions`, `ModeActivationLogic`, `BlockDecider`), compiler-forbidden from importing Android, tested as plain JVM. `:app` is everything else: Compose UI, services, receivers, widget.

Inside `:app` the layers run one direction — **Domain → Data → Side effects → Service/UI** — with one owner each:

- **`AppStateRepository`** owns persisted state. Every mutation is a mutex-guarded `update { transform }`; exactly one file in the repo knows the storage key.
- **`StateSyncer`** owns platform side effects. The repo invokes it after each write, and it restarts `BlockerService`, reschedules alarms, diffs timed unlocks, refreshes widgets. Exactly two `BlockerService.start(…)` callsites exist, both inside it.
- **`BlockerService`** hosts three collaborators: `ForegroundAppDetector`, `BlockDecider`, and an `Enforcer` — an overlay when accessibility is off, a force-close when it's on, picked per tick.

[CLAUDE.md](CLAUDE.md) has the long version, including which invariants to not break.

## Config

Settings exports modes, schedules and tags as JSON or YAML — config only, never runtime state. Import either replaces everything or merges by id.

<details>
<summary>Example</summary>

```yaml
version: 1
modes:
  - id: "abc-123"
    name: "Work Focus"
    blockMode: BLOCK_SELECTED
    nfcTagIds: ["tag-001", "tag-002"]
    blockedApps:
      - "com.instagram.android"
      - "com.reddit.frontpage"
schedules: []
nfcTags:
  - id: "tag-001"
    name: "Home tag"
    unlockDurationMinutes: null   # permanent
  - id: "tag-002"
    name: "Work tag"
    unlockDurationMinutes: 5      # five minutes, then back on
```

</details>

## License

MIT. Issues and pull requests welcome.

<sub>Claude was used during development for code and UI design.</sub>
