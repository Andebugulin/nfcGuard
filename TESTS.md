# Testing

418 tests, 0 failures — 323 on the JVM, 95 on a real device.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk   # AGP needs JDK 17+

./gradlew :domain:test              # 78 tests, pure Kotlin, ~3s
./gradlew :app:testDebugUnitTest    # 245 tests, Robolectric, ~30s
./gradlew test                      # both of the above
```

`./gradlew test` means `:domain:test` + `:app:testDebugUnitTest`, because the
release unit-test variant is switched off in `app/build.gradle.kts`. It has to
be: the Compose test manifest that supplies the `ComponentActivity` every
`createComposeRule` test launches into ships as `debugImplementation`, so a
Compose test in the release variant dies with *"Unable to resolve activity for
Intent … androidx.activity.ComponentActivity"*. Release unit tests would re-run
identical sources for no extra signal — minification does not apply to unit
tests — so the variant is disabled rather than worked around.

### Instrumented suite (real device)

Do **not** use `./gradlew :app:connectedDebugAndroidTest`: it reinstalls the
APK on every run, and a reinstall wipes the appops grants and the accessibility
setting the suite depends on. Install once, then drive the runner directly:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
bash scripts/grant-test-permissions.sh
adb shell am instrument -w \
  com.andebugulin.nfcguard.test/androidx.test.runner.AndroidJUnitRunner
```

`scripts/grant-test-permissions.sh` also sets `hidden_api_policy=1`, which the
simulated NFC taps need (see [Simulating an NFC tap](#simulating-an-nfc-tap)).
Restore the device default afterwards with
`adb shell settings delete global hidden_api_policy`.

On Xiaomi/HyperOS `-t` matters (`adb install -r -t`); without it some builds
reject the test APK. Older HyperOS releases gate "Install via USB" behind a
Mi account and reject `pm install` with `INSTALL_FAILED_USER_RESTRICTED`, which
needs a manual tap per install.

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
| `:app` service | 1,244 | Robolectric | **covered** (23 tests) |
| `:app` viewmodel | 473 | Robolectric | **covered** (18 tests) |
| `:app` Compose screens | 7,699 | Robolectric + Compose | **covered** (116 tests) |
| `:app` widget | 308 | Robolectric | **covered** (16 tests) |
| `:app` first-run showcase | 226 | Robolectric | **covered** (8 tests) |
| app end-to-end (nav, NFC, dialogs, emergency reset, tag caps, schedules) | — | instrumented | **covered** (64 tests) |
| device behaviour | — | instrumented | **covered** (22 tests) |

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
| `ForceCloseEnforcerTest` | 8 | the 3-second cooldown and exactly what resets it |
| `BlockerServiceScreenGateTest` | 4 | enforcement gated on interactive + unlocked |
| `ForegroundDetectorServiceTest` | 3 | accessibility reconnect restores blocking |

`ForceCloseEnforcer` was previously listed as device-only ("needs a device test
that can observe the launcher coming forward"). Observing the launcher is not
necessary: the HOME **intent** is the observable, and with
`ForegroundDetectorService.isRunning` forced false the accessibility path is
skipped and the documented Intent fallback runs deterministically. That makes
the cooldown rules testable on the JVM — including the two that are easy to get
wrong, and which the KDoc calls out: passing through the **launcher** or a
**critical system app** must *not* reset the cooldown, or a stale accessibility
event ejects the user out of whatever they opened next.

What stays device-only is whether accessibility's `goHome()` actually moves the
launcher on a given OEM build.

## `:app` widget

`GuardianWidgetTest` (16). Robolectric's `ShadowAppWidgetManager` inflates the
real `RemoteViews`, so rendered text can be read back, and the buttons are plain
broadcasts, so `onReceive` can be called directly — no device needed.

Covers every render state (no modes, inactive, active, active-and-timed,
conflicting polarity, stackable same-polarity), mode and duration cycling
including wrap-around in both directions, activation as a *manual* activation,
timed activation, conflict refusal, clearing a pending NFC reactivation,
`onDeleted` forgetting per-widget prefs, and a stale selection index being
clamped rather than crashing when modes are deleted in the app.

The action strings are duplicated in the test on purpose. They are private to
the provider but they are also a *published contract* — baked into
`PendingIntent`s that live in the launcher's process and survive app upgrades.
Pinning the literals means renaming one breaks the test, which is the point:
already-placed widgets would silently stop working.

## `:app` viewmodel and UI

`GuardianViewModelTest` (18) covers what is not mere delegation: the
safe-regime flag and challenge duration that deliberately live outside
`AppState` (so a config import cannot weaken the safety gate), the 90-second
raise-only floor, import replace vs merge, orphan tag cleanup, and the NFC
unlock round-trip.

| Suite | Tests | Covers |
|---|---|---|
| `SettingsDialogTest` | 15 | permission rows, the anti-bypass toggle and its gate, blocking method, data section |
| `PermissionOnboardingTest` | 12 | the step machine past WELCOME: the permission queue, the pause reminder, OEM branching |
| `ModesScreenTest` | 13 | empty state, listing, both polarities, active badge, delete dialog (incl. naming affected schedules), activation dialog |
| `UnlockDurationDialogTest` | 7 | uncapped unlock paths, multi-mode selection, the last mode not being deselectable |
| `NfcTagsScreenTest` | 8 | empty state, listing, unlinked-tag notice, delete dialog |
| `FeatureShowcaseTest` | 8 | per-screen content, the lost-tag recovery tip, sticky per-screen "seen" |
| `OnboardingScreenTest` | 6 | the carousel and the "GET STARTED" handoff that #12 crashed on |
| `SchedulesScreenTest` | 5 | "create modes first" gate, creation once a mode exists, listing |
| `ConfigFileTransferTest` | 10 | export/import file I/O and the YAML-vs-JSON heuristic |
| `LogViewerDialogTest` | 6 | the event log behind a bug report, and the share hand-off |
| `InfoScreenTest` | 5 | renders offline, both enforcement explanations, bug-report entry |
| `ModeEditorScreenTest` | 16 | the app picker — search, selection, critical-app filtering — and both polarities |
| `SafeRegimeChallengeDialogTest` | 4 | never completes early, giving up cancels rather than satisfies |
| `HomeScreenTest` | 3 | renders empty, with modes, with an active mode |

Four Compose gotchas this codebase hits, all encoded in the suites:

- **Names render `.uppercase()`.** Assert `"DEEP WORK"`, not `"Deep Work"`.
- **Below-the-fold content in a `LazyColumn` is not composed.** Reach it with
  `onNode(hasScrollAction()).performScrollToNode(hasText(...))`.
- **`HomeScreen` runs endless `LaunchedEffect` loops** (2s permission refresh,
  30s poll), so the auto-advancing clock never reaches idle. Set
  `compose.mainClock.autoAdvance = false` and advance by frame.
- **A dialog's button label is usually also on the screen behind it** — the mode
  card's own DELETE sits under the delete dialog's DELETE. Scope the match with
  `hasAnyAncestor(isDialog())` rather than guessing at node order.

### A dialog with a text field cannot be tested under Robolectric

This is the one real boundary in the JVM UI suite, and it is worth knowing
before you spend an afternoon on it.

A Compose dialog containing an `OutlinedTextField` **never reaches idle** under
Robolectric. Composition spins until Espresso gives up after 60 seconds with
`AppNotIdleException: Compose did not get idle after ~450000 attempts`. It is
not the clock, so `mainClock.autoAdvance = false` does not help; and it is not
the app, since an otherwise identical dialog with the text field removed is
fine in the same graphics mode. It reproduces in a ten-line test with a
hand-written `AlertDialog`, in both `NATIVE` and `LEGACY` graphics.

So every branch that needs typing — the mode create dialog, the tag register
and rename dialogs, and the unlock dialog's *capped* paths (a cap opens the
dialog on the timed option, which renders the HOURS/MINUTES fields) — is
covered on a real device in `DialogFlowsEndToEndTest`, where it all works. The
delete and activation dialogs have no text field, so they stayed on the JVM.

A future alternative: the unlock dialog's limit arithmetic (resolve the
effective limit across selected modes, clamp the typed duration) is pure logic
living in a composable. Extracting it into `:domain` would make the app's most
safety-critical rules testable in milliseconds. That is a production refactor,
not a test change, so it has not been done here.

**Testing note:** `GuardianViewModel.init` starts an endless 5-second polling
loop on `viewModelScope`. `runTest` hangs on it, because its cleanup runs
`advanceUntilIdle` against virtual time that never drains. Use a plain
`@Test` with `UnconfinedTestDispatcher`, and cancel the ViewModel in
`@After`.

## Instrumented suite (95 tests)

Split in two: the end-to-end suites that drive the real app through its own
entry point, and the device-behaviour suites that answer what the JVM cannot.

| Suite | Tests | Covers |
|---|---|---|
| `DialogFlowsEndToEndTest` | 19 | every dialog branch that needs typing — unlock caps, tag naming, the challenge-duration floor |
| `EmergencyResetEndToEndTest` | 10 | the lost-tag escape hatch and the challenge that gates it |
| `ScheduleEditorEndToEndTest` | 22 | building and editing a schedule, per-day times, and the gate on active edits |
| `TagLimitEndToEndTest` | 11 | where per-tag unlock caps are *set*, and that they bind at unlock |
| `DeviceBehaviourTest` | 8 | live `UsageStatsManager` detection, real accessibility binding, the #13 gate against real `PowerManager`/`KeyguardManager`, real SharedPreferences persistence |
| `EnvironmentTest` | 7 | preflight — every grant the suite needs, each failure naming its own `adb` fix |
| `NfcTapEndToEndTest` | 6 | simulated taps: register, unlock, wrong tag, cold start |
| `AppNavigationEndToEndTest` | 5 | navigation, Back semantics, first-run onboarding, create/activate persistence |
| `OverlayEnforcerInstrumentedTest` | 4 | a real `TYPE_APPLICATION_OVERLAY` window: show, hide, double-block idempotence, teardown |
| `MockNfcTagProbeTest` | 3 | that a `Tag` really can be fabricated on this device |

The whole run takes around 170 seconds. Keep it that way: no test should sit
through wall-clock waits. See the note on the attention challenge below for the
one case where that was tempting.

### The emergency reset — the only flow that can switch blocking off

`EmergencyResetEndToEndTest` covers the lost-tag escape hatch, which had no
coverage at all: the only mention of "Emergency Reset" anywhere in the suites
was `FeatureShowcaseTest` asserting that a *tip* about it renders.

The branch that matters is `HomeScreen`'s decision on CONTINUE — challenge when
modes are active, straight through when none are. Both halves are pinned, along
with the stricter property that the gate does **not** consult
`safe_regime_enabled`: that setting lives outside `AppState` so an imported
config cannot weaken the safety challenge, and a gate a toggle could switch off
would undo that. Every abort path (cancel the warning, give up the challenge,
cancel tag selection, confirm with nothing selected) is asserted to leave modes
active and tags intact.

**Actually passing the challenge is deliberately not automated.** It is a
90-second attention gate — 15 seconds of waiting, then a 5-second window to
press, repeatedly — and it cannot be shortened from a test. The duration floor
is raise-only, and the countdown runs on *real* time: `mainClock.advanceTimeBy`
drives recomposition but not the dialog's `delay()`, so pausing Compose's clock
only freezes the countdown instead of fast-forwarding it. A test that presses
through it added ~100 seconds to every run for a single assertion, and was
removed. The gate itself — required, skipped, given up, and nothing changing on
any of those paths — is covered above in milliseconds.

### Where unlock caps are set

`TagLimitEndToEndTest` covers `TagLimitConfigDialog`, closing an asymmetry: the
suite pinned that a cap is *enforced* at unlock time, but nothing covered the
screen that writes `tagUnlockLimits`, because the enforcement tests seed the map
directly. A bug storing the wrong key or number would have sailed through.

It covers the round-trip (set 30 minutes, read back "0H 30M", reopen prefilled),
clearing a cap back to permanent, cancel keeping the old value, the `ANY`
wildcard storing under its literal key, and the "NO PERMANENT UNLOCK" guard that
fires when *every* selected tag is capped — including that backing out of that
warning writes nothing. The last test joins both ends: a cap typed into the
editor is the cap the unlock dialog honours after a tag tap.

### Config export and import

`ConfigFileTransferTest` covers the file I/O above `ConfigManager`: writing the
chosen document, reading one back, and the heuristic that decides whether a file
is YAML or JSON — misroute that and a valid backup reports as corrupt. It also
pins that a corrupt file costs the user nothing, and that neither format is
applied until they pick MERGE or REPLACE.

Reaching the launchers needs no production change:
`rememberLauncherForActivityResult` resolves `LocalActivityResultRegistryOwner`,
so the test supplies a registry that records the launch and hands back a URI on
demand, with Robolectric's ContentResolver backing that URI with an in-memory
stream. Two things are required to make it work, both easy to lose an afternoon
to: the callbacks write Compose state from *outside* the composition, so
`Snapshot.sendApplyNotifications()` is needed before advancing frames or nothing
recomposes (the bytes land, but no dialog and no message ever appear); and the
status message renders `.uppercase()` like everything else.

### The settings sheet and the permission flow

`SettingsDialogTest` covers the sheet on the JVM — it holds no text field of its
own, only `ChallengeDurationDialog` does, so that one dialog is covered on
device in `DialogFlowsEndToEndTest` (the 1:30 floor is *refused*, not silently
coerced) and everything else runs in milliseconds.

The gate here is a third variant, and all three are now pinned side by side:

| Action | Gated when |
|---|---|
| Emergency reset | `activeModes.isNotEmpty()` — ignores the toggle |
| Schedule edit/delete | `safeRegimeEnabled && activeModes.isNotEmpty()` |
| Switching the toggle off | `activeModes.isNotEmpty()` |

`PermissionOnboardingTest` covers the flow past its first step — it is a dialog
state machine, not a screen, and none of its dialogs holds a text field, so it
runs on the JVM against the real permission state that drives it: the queue
advancing in order, GRANT firing the right Settings intent, the pause reminder
marking the flow complete, and the manufacturer branching (Pixel and Samsung are
told accessibility is required; everyone else is offered it). This is the
neighbourhood issue #12 lived in.

Note that `ChallengeDurationDialog`'s row sits *outside* the
`if (safeRegimeEnabled)` block, so it stays configurable with protection off.
That is pinned as current behaviour rather than asserted as correct.

### Schedules, and two gates that differ on purpose

`ScheduleEditorEndToEndTest` covers `ScheduleEditorDialog`: that a schedule
assembled in the UI reaches the repository with the right days and links, that
all three of name, day and linked mode are required (a schedule linked to
nothing would fire and do nothing), duplicate names, day toggling, and delete
with its confirmation.

It also pins the challenge on editing or deleting a schedule *while a mode is
active* — and deliberately pins that this gate reads `safeRegimeEnabled`, where
the emergency reset's does not. The asymmetry is intentional: the emergency
reset is the last way back in when a tag is lost, so its gate is
unconditional. Both directions are asserted so neither is "harmonised" into the
other by accident.

The clock picker the editor opens is covered on the JVM instead — see
`ModernTimePickerDialogTest`, which is where the tap and rounding fix in
`ClockFace` is pinned.

### Simulating an NFC tap

TESTS.md used to list NFC scanning as permanently uncoverable — "requires
physically tapping a tag; no harness can simulate it". That turns out to be
wrong. The NFC stack hands the Activity an `ACTION_TECH_DISCOVERED` intent
carrying an `android.nfc.Tag` parcelable; everything the app does is downstream
of that intent. `MockNfcTag` fabricates the `Tag`, and the whole flow runs
unmodified: hex encoding, the wrong-tag guard, `MainNavigation`'s routing
`LaunchedEffect`, the unlock dialog, and the resulting state write.

Three things make it work:

- **There is no public way to build a `Tag`.** No public constructor; the only
  factory is the `@hide` `Tag.createMockTag`. Its signature has changed across
  platform versions (Android 14 added a trailing `long` cookie), so
  `MockNfcTag` matches parameters **by type at runtime** instead of pinning one
  release.
- **The hidden-API blocklist hides it from reflection entirely** — on API 35
  `Tag::class.java.declaredMethods` does not even list `createMockTag` until
  `adb shell settings put global hidden_api_policy 1`. That is why
  `MockNfcTagProbeTest` exists and why the suites `Assume` on
  `MockNfcTag.isSupported()`: without the policy the tests skip loudly instead
  of passing vacuously.
- **Two delivery paths, both covered.** A tap on the already-open app goes to
  `onNewIntent`; a tap that wakes the app from cold goes through `onCreate`.
  `tapNfcTagFromColdStart` uses a genuine `ActivityScenario.launch(intent)`, so
  AMS delivery is exercised for real. The warm path calls `onNewIntent`
  directly — see the teardown note below for why.

What is still **not** covered: the radio itself and `enableForegroundDispatch`.
Those belong to the platform.

### The end-to-end harness

`GuardianHarness` + `Robots.kt` (in `app/src/androidTest/.../harness/`) drive
the real app: seed a known state, launch `MainActivity`, then interact as a
user. The Robolectric screen suites compose one screen at a time with a
ViewModel they own, so they cannot see the seams between screens — the
`when (currentScreen)` dispatch, the BackHandler that sends sub-screens Home
instead of exiting, the onboarding gate, or whether a mode created through the
UI actually survives in `AppStateRepository`. That is what these cover.

Robots are text-driven page objects (the app sets no `testTag`s) and encode the
app's UI conventions once: names render uppercased, lists are `LazyColumn`s so
off-screen rows must be scrolled into composition, and dialog controls are
matched with `isDialog()`.

Four things learned the hard way here, all encoded in the harness:

- **Click the node that owns the click action, not the label.** A label matches
  *two* nodes: the clickable container (whose merged semantics include the
  label) and the inner `Text`. `onFirst()` can land on the `Text`, and the tap
  is then silently swallowed — the test reports success while nothing happened.
  The unlock dialog's mode rows behaved exactly that way. `Robot.click` prefers
  `matcher and hasClickAction()`.
- **Never let `ActivityScenario.close()` drive teardown.** It works through
  `InstrumentationActivityInvoker`, whose helper Activity is hosted in the
  *test* package's own process. Once the app is in front, that process goes
  cached and the system reaps it (`lowmemorykiller … adj=900`, confirmed in
  logcat on this Xiaomi). `close()` then blocks for its full 45-second timeout
  waiting for a DESTROYED it can no longer cause — every test failed in
  teardown with a perfectly healthy body, and the suite took 230s instead of 7s.
  `GuardianHarness.close` calls `finish()` in-process and waits on the
  lifecycle monitor.
- **Do not seed an *active* mode.** `StateSyncer` would start `BlockerService`
  while the app is in the background, and Android answers the late
  `startForeground` with `ForegroundServiceDidNotStartInTimeException`, taking
  the whole process down. Seed **config** only and activate through the UI,
  which is a legal foreground start and what a user does anyway.
- **Wait for background loads explicitly.** `waitForIdle` knows nothing about a
  coroutine on `Dispatchers.IO`, so the mode editor's app picker is briefly
  settled *and* empty. `Robot.waitFor` waits for the node.
- **Never wait with `Thread.sleep`.** A Compose test rule drives the clock that
  composed `delay(...)` loops tick on, and that clock only advances while the
  test framework is pumping. Sleeping on the test thread freezes them: the
  safe-regime countdown sat at 1:30 on screen for the whole test, which looks
  exactly like an app-side race and is not one — the same dialog ticks
  correctly when the app is driven by hand with `adb shell input`. Use
  `compose.waitUntil`, which keeps the framework pumping.
- **Scroll on *displayed*, never on present.** A `verticalScroll` column
  composes every child, so "does this node exist?" is always true and a
  scroll-before-tap that asks it never scrolls — the tap then lands off-screen
  and silently does nothing, which reads as a dead button. (A `LazyColumn`
  hides this, because its off-screen rows really are absent.) `Robot.scrollTo`
  checks `assertIsDisplayed` instead.
- **Assert on displayed, with a wait.** A dialog animates in, so its buttons are
  composed a frame or two before they are on screen, and `waitForIdle` can
  return inside that window. `Robot.assertVisible` waits for display.
- **Identify a row by a sibling, not by order.** Every tag row shows
  "PERMANENT" until a cap is set, so the label alone is ambiguous;
  `hasAnySibling(hasText(name))` picks the right row's button without depending
  on the order rows happen to render in.

State is seeded through `AppStateRepository.update`, not by nulling the
singleton as the Robolectric fixtures do: instrumentation shares a process with
the app, so it is the *same* singleton the running Activity observes. Resetting
it would leave two repositories writing one prefs file and the Activity holding
the dead one.

The picker's app list comes from the app's own `loadInstalledApps`, so the tests
do not assume Chrome is installed and stay in step with critical-app filtering.

### Device-behaviour notes

- **Never `runBlocking` inside `runOnMainSync`** when driving `OverlayEnforcer`.
  Its show/hide animations post completion callbacks to the main looper, which
  a blocked main thread can never drain — instant deadlock.
- **Starting `BlockerService` from an instrumentation context kills the app**,
  as above. There is deliberately no instrumented test that starts the service;
  its Intent contract is asserted by `StateSyncerTest` instead.

Accessibility assertions use `Assume` rather than `assertTrue`: `am instrument`
restarts the target process and the system rebinds an AccessibilityService on
its own schedule, so those tests skip instead of failing when the rebind has
not landed.

## Regression tests tied to real issues

Each production bug now has a test that fails without its fix.

| Issue | Test | Reproduces |
|---|---|---|
| [#12](https://github.com/Andebugulin/nfcGuard/issues/12) Galaxy S8 crash | `PermissionsTest`, `AppLoggerTest`, `AppNavigationEndToEndTest` | `unsafeCheckOpNoThrow` is API 29+; on API 26/28 it raised `NoSuchMethodError`, which `catch (Exception)` does not catch. The e2e test walks the whole carousel and asserts the permission flow takes over, which is the handoff that crashed |
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

1. **`ForceCloseEnforcer`'s accessibility path.** The cooldown and the HOME
   Intent fallback are covered; whether `goHome()` actually moves the launcher
   is per-OEM and still a manual check.
2. **The system share sheet.** `FileProvider` hand-off for bug reports and
   config export — the app's side is covered, the chooser itself is not.
3. **`BlockerService`'s own lifecycle.** Its collaborators and the screen gate
   are covered; `onStartCommand`, the notification and per-tick enforcer
   selection are not, and starting the service under instrumentation kills the
   process.
4. **The NFC radio and `enableForegroundDispatch`.** Everything downstream of
   the intent is covered; the radio is the platform's.

Manual device checks that no suite replaces: a real tag tap against a real
radio, force-closing a blocked app with accessibility on, and the widget on an
actual launcher.

## Adding tests

- Pure logic with no Android types → `:domain`, plain JUnit. Prefer this.
- Anything touching `Context`, prefs, alarms, services → `:app`, Robolectric.
- Anything that needs typing into a dialog, the real Activity, or a simulated
  NFC tap → `:app` `androidTest`, via `GuardianHarness` and the robots.
- Domain builders (`mode()`, `schedule()`, `tag()`, `emptyState()`) live in
  `app/src/testShared/java/.../testing/Builders.kt`, wired into **both** the
  `test` and `androidTest` source sets in `app/build.gradle.kts`. Robolectric-only
  helpers stay in `app/src/test/java/.../testing/Fixtures.kt`, because
  Robolectric is not on the instrumented classpath.
- Call `resetAppStateRepository()` in `@Before` for Robolectric tests — it is a
  process-wide singleton and would otherwise leak a dead `Context`. Do **not**
  do this in instrumented tests; seed through the repository instead.
- Call `grantOverlayPermission()` whenever a path reaches
  `BlockerService.start`; it early-returns without it.
- Use `setDetectorState("isRunning", …)` to pose as accessibility being bound
  or not; the field is `private set`.
- Name tests as sentences describing the behaviour, not the method.
