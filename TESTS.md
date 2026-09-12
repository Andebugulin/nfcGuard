# Testing

232 tests, 0 failures — 213 on the JVM, 19 on a real device.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk   # AGP needs JDK 17+

./gradlew :domain:test              # 78 tests, pure Kotlin, ~3s
./gradlew :app:testDebugUnitTest    # 135 tests, Robolectric, ~50s
./gradlew test                      # both JVM suites
```

### Instrumented suite (real device)

Do **not** use `./gradlew :app:connectedDebugAndroidTest`: it reinstalls the
APK on every run, and a reinstall wipes the appops grants and the accessibility
setting the suite depends on. Install once, then drive the runner directly:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
bash scripts/grant-test-permissions.sh
adb shell am instrument -w \
  com.andebugulin.nfcguard.test/androidx.test.runner.AndroidJUnitRunner
```

On Xiaomi/HyperOS each install needs a manual tap — "Install via USB" is
Mi-account gated and `pm install` is rejected outright with
`INSTALL_FAILED_USER_RESTRICTED`.

First Robolectric run downloads `android-all` jars per API level (several
minutes, once). Cached runs are seconds. Add `--rerun-tasks` when results look
suspiciously `UP-TO-DATE`.

HTML reports: `domain/build/reports/tests/test/index.html`,
`app/build/reports/tests/testDebugUnitTest/index.html`.

## What is tested, and with what

| Layer | LOC | Tool | Status |
|---|---|---|---|
| `:domain` — pure state math | 779 | JUnit | **covered** (78 tests) |
| `:app` data | 971 | Robolectric | **covered** (41 tests) |
| `:app` sync | 243 | Robolectric | **covered** (10 tests) |
| `:app` receiver | 474 | Robolectric | **covered** (10 tests) |
| `:app` service | 1,244 | Robolectric | **partial** (15 tests) — enforcers untested |
| `:app` viewmodel | 473 | Robolectric | **covered** (18 tests) |
| `:app` Compose screens | 7,699 | Robolectric + Compose | **covered** (40 tests) |
| `:app` widget / showcase / theme | 640 | — | **not covered** |
| device behaviour | — | instrumented | **covered** (19 tests) |

The split is deliberate rather than accidental: everything above the UI line is
reachable on the JVM, and that is where all three reported production bugs
lived. The UI layer is the honest gap — see [Gaps](#gaps).

## `:domain` — pure state math

Plain JUnit, no Android. The Gradle boundary forbids Android imports, which is
what keeps these tests fast and deterministic.

| Suite | Tests | Covers |
|---|---|---|
| `NfcUnlockLogicTest` | 25 | `computePendingUnlock`, `applyUnlock`, `applyReactivation` |
| `ModeActivationLogicTest` | 16 | manual activate/deactivate, strict conflict rejection |
| `ScheduleTransitionsTest` | 15 | alarm-driven activation, permissive conflict skipping |
| `BlockDeciderTest` | 12 | critical-app / launcher / polarity decisions |
| `NfcUnlockFlowTest` | 5 | end-to-end unlock incl. the paused-timer round-trip |
| `ScheduleFlowTest` | 5 | end-to-end schedule start → end |

## `:app` data

| Suite | Tests | Covers |
|---|---|---|
| `AppStateRepositoryTest` | 11 | persistence, reload, no-op short-circuit, `updateWith`, corrupt-JSON fallback, legacy `nfcTagId` migration (incl. write-back) |
| `ConfigManagerTest` | 11 | JSON + YAML round-trips, block mode / tag links / per-tag limits, quotes and colons in names, **runtime state never exported** |
| `PermissionsTest` | 10 | usage-access probe across API 26/28/29/33/34 |
| `AppLoggerTest` | 9 | ordering, category tag, 1000-entry cap, oldest-dropped-first, report build across API levels |

## `:app` sync and receivers

`StateSyncerTest` (10) pins the arguments `BlockerService.start` receives,
since `StateSyncer` is the only place allowed to dispatch it: BLOCK/ALLOW
polarity, the app union across active modes, **ALLOW precedence without BLOCK
apps leaking into the allowlist**, keep-alive vs stop, alarm diffing, and
idempotence.

`ReceiversTest` (10) treats receivers as the thin adapters they are — that the
right action reaches the right transform and state actually changes. Timed
deactivate/reactivate, unknown and missing mode ids, watchdog revival and
self-chaining, boot restore, service-restart re-sync.

## `:app` service

| Suite | Tests | Covers |
|---|---|---|
| `ForegroundAppDetectorTest` | 8 | all three strategies, priority order, and the "load-bearing" resume-after-pause timestamp comparison |
| `BlockerServiceScreenGateTest` | 4 | enforcement gated on interactive + unlocked |
| `ForegroundDetectorServiceTest` | 3 | accessibility reconnect restores blocking |

## `:app` viewmodel and UI

`GuardianViewModelTest` (18) covers what is not mere delegation: the
safe-regime flag and challenge duration that deliberately live outside
`AppState` (so a config import cannot weaken the safety gate), the 90-second
raise-only floor, import replace vs merge, orphan tag cleanup, and the NFC
unlock round-trip.

Every screen has a Compose suite running under Robolectric — no device needed:

| Suite | Tests | Covers |
|---|---|---|
| `OnboardingScreenTest` | 6 | the carousel and the "GET STARTED" handoff that #12 crashed on |
| `ModesScreenTest` | 8 | empty state, listing, both polarities, active badge |
| `NfcTagsScreenTest` | 6 | empty state, registration entry, listing, unlinked-tag notice |
| `SchedulesScreenTest` | 5 | "create modes first" gate, creation once a mode exists, listing |
| `InfoScreenTest` | 4 | renders offline, both enforcement explanations, bug-report entry |
| `ModeEditorScreenTest` | 4 | both polarities, NFC tag lock section |
| `SafeRegimeChallengeDialogTest` | 4 | never completes early, giving up cancels rather than satisfies |
| `HomeScreenTest` | 3 | renders empty, with modes, with an active mode |

These are render-and-key-interaction tests, not exhaustive branch coverage —
they catch "this screen crashes or stops rendering", which is the class of bug
issue #12 was.

Three Compose gotchas this codebase hits, all encoded in the suites:

- **Names render `.uppercase()`.** Assert `"DEEP WORK"`, not `"Deep Work"`.
- **Below-the-fold content in a `LazyColumn` is not composed.** Reach it with
  `onNode(hasScrollAction()).performScrollToNode(hasText(...))`.
- **`HomeScreen` runs endless `LaunchedEffect` loops** (2s permission refresh,
  30s poll), so the auto-advancing clock never reaches idle. Set
  `compose.mainClock.autoAdvance = false` and advance by frame.

**Testing note:** `GuardianViewModel.init` starts an endless 5-second polling
loop on `viewModelScope`. `runTest` hangs on it, because its cleanup runs
`advanceUntilIdle` against virtual time that never drains. Use a plain
`@Test` with `UnconfinedTestDispatcher`, and cancel the ViewModel in
`@After`.

## Instrumented suite (19 tests)

What the JVM cannot answer:

| Suite | Tests | Covers |
|---|---|---|
| `EnvironmentTest` | 7 | preflight — every grant the suite needs, each failure naming its own `adb` fix |
| `DeviceBehaviourTest` | 8 | live `UsageStatsManager` detection, real accessibility binding, the #13 gate against real `PowerManager`/`KeyguardManager`, real SharedPreferences persistence |
| `OverlayEnforcerInstrumentedTest` | 4 | a real `TYPE_APPLICATION_OVERLAY` window: show, hide, double-block idempotence, teardown |

Two things learned the hard way, both encoded in the tests:

- **Never `runBlocking` inside `runOnMainSync`** when driving `OverlayEnforcer`.
  Its show/hide animations post completion callbacks to the main looper, which
  a blocked main thread can never drain — instant deadlock.
- **Starting `BlockerService` from an instrumentation context kills the app.**
  Android raises `ForegroundServiceDidNotStartInTimeException` asynchronously
  and takes the process down, which also unbinds the accessibility service and
  leaves it in the system's `Crashed services` list. There is deliberately no
  instrumented test that starts the service; its Intent contract is asserted
  by `StateSyncerTest` instead.

Accessibility assertions use `Assume` rather than `assertTrue`: `am instrument`
restarts the target process and the system rebinds an AccessibilityService on
its own schedule, so those tests skip instead of failing when the rebind has
not landed.

## Regression tests tied to real issues

Each production bug now has a test that fails without its fix.

| Issue | Test | Reproduces |
|---|---|---|
| [#12](https://github.com/Andebugulin/nfcGuard/issues/12) Galaxy S8 crash | `PermissionsTest`, `AppLoggerTest` | `unsafeCheckOpNoThrow` is API 29+; on API 26/28 it raised `NoSuchMethodError`, which `catch (Exception)` does not catch |
| [#13](https://github.com/Andebugulin/nfcGuard/issues/13) app opens on unlock | `BlockerServiceScreenGateTest` | screen-off detection fell back to "last used app", so the overlay appeared over the lock screen |
| [#10](https://github.com/Andebugulin/nfcGuard/issues/10) force-stop bypass | `ForegroundDetectorServiceTest` | force-stop kills alarms and broadcasts; the rebound accessibility service is the only recovery hook |

## Multi-SDK testing

The single highest-value technique here, and the direct lesson from #12.

```kotlin
@Config(sdk = [26, 28, 29, 33, 34])
fun `reports granted when the op is allowed, on every supported API level`()
```

Robolectric loads the real `android-all` jar for each SDK, so a method that
does not exist on API 28 genuinely is absent — the same `NoSuchMethodError` a
Galaxy S8 user hit. `minSdk = 26` and `targetSdk = 36` span ten API levels that
cannot be checked by hand.

**Any code touching a version-gated API should carry a multi-SDK `@Config`.**

Robolectric 4.15 supports up to SDK 35, so `app/src/test/resources/robolectric.properties`
pins the default to 34. Tests that care declare their own range.

## Gaps

Not covered, in rough priority order:

1. **`GuardianWidget` (308).** Button actions and rendering. Needs
   `AppWidgetManager` fakes or an instrumented test.
2. **`ForceCloseEnforcer` (116).** Sends HOME through accessibility; needs a
   device test that can observe the launcher coming forward.
3. **`FeatureShowcase` (226).** First-run popups.
4. **Dialog branches inside the covered screens.** The suites assert each
   screen renders and its primary controls work; the create/edit/delete
   dialogs and their validation paths are largely unexercised.
5. **NFC tag scanning.** Requires physically tapping a tag; no harness can
   simulate it. The unlock *logic* is fully covered in `:domain` and the
   ViewModel, so only the `MainActivity` intent plumbing is unverified.

Manual device checks that no suite replaces: widget buttons, force-closing a
blocked app with accessibility on, and one NFC unlock round-trip.

## Adding tests

- Pure logic with no Android types → `:domain`, plain JUnit. Prefer this.
- Anything touching `Context`, prefs, alarms, services → `:app`, Robolectric.
- Use the builders in `app/src/test/java/.../testing/Fixtures.kt`
  (`mode()`, `schedule()`, `tag()`).
- Call `resetAppStateRepository()` in `@Before` — it is a process-wide
  singleton and would otherwise leak a dead `Context` between tests.
- Call `grantOverlayPermission()` whenever a path reaches
  `BlockerService.start`; it early-returns without it.
- Name tests as sentences describing the behaviour, not the method.
