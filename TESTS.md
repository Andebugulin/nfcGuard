# Testing

155 tests, 0 failures. Two suites, both plain JVM — no device, no emulator.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk   # AGP needs JDK 17+

./gradlew :domain:test              # 78 tests, pure Kotlin, ~3s
./gradlew :app:testDebugUnitTest    # 77 tests, Robolectric, ~20s
./gradlew test                      # everything
```

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
| `:app` ui / viewmodel / widget | 9,250 | — | **not covered** |

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

1. **Compose UI — ~8,400 LOC.** `HomeScreen` (1,765), `SchedulesScreen`
   (1,373), `ModesScreen` (1,236), `NfcTagsScreen` (845), `ModeEditorScreen`
   (809), `InfoScreen` (783), `MainActivity` (566), `PermissionOnboarding`
   (438), `SafeRegimeChallengeDialog` (322), `FeatureShowcase` (226).
   Reachable via `compose-ui-test` under Robolectric. `PermissionOnboarding`
   deserves it first — it is the #12 crash path.
2. **`GuardianViewModel` (473).** Thin over the repository, but the 5-second
   polling safety net and `importConfig` orphan cleanup are untested.
3. **Enforcers (513).** `OverlayEnforcer` and `ForceCloseEnforcer` need
   `WindowManager` / accessibility fakes. The block/allow *decision* is covered;
   the *execution* is not.
4. **`GuardianWidget` (308).** Button actions and rendering.
5. **NFC hardware paths.** Device-only; emulator NFC is unusable. The unlock
   *logic* is fully covered in `:domain` — only the `MainActivity` intent
   plumbing is not.

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
