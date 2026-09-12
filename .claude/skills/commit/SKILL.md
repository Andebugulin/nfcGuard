---
name: commit
description: Create a professional git commit for nfcGuard - inspect the diff, stage deliberately, run the secret and build gates, and write a Conventional Commits message. Use for "commit", "commit this", "write a commit message", or /commit.
allowed-tools: Bash, Read, Grep, Glob
---

# Commit (nfcGuard)

Produce one reviewable commit with a message that survives `git log` two years from now.
This project skill supersedes the global `commit` skill inside this repository.

## Procedure

1. **Inspect.** `git status --short`, `git diff --staged`, `git diff`. Never commit a diff you have
   not read.
2. **Scope.** One logical change per commit. If the diff spans unrelated concerns, split it into
   separate commits rather than joining subjects with `+`.
3. **Stage deliberately.** Name paths explicitly. Never `git add -A` or `git add .` — they sweep in
   scratch files and unrelated edits.
4. **Run the secret gate** (below). Non-negotiable.
5. **Run the build gate** (below), sized to the change.
6. **Write the message** to the format below. Commit with a heredoc so the body keeps its newlines:
   `git commit -F - <<'EOF' … EOF`.
7. **Report** the resulting subject and short hash. Do not push unless asked.

## Format

```
<type>(<scope>): <subject>

<body>

<footers>
```

**Types** — `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `revert`.
`refactor` means behavior is unchanged; if behavior changed, it is `feat` or `fix`.

**Scopes** — use an existing one; extend the list deliberately, not ad hoc.

| Scope | Covers |
|---|---|
| `service` | `BlockerService`, enforcers, foreground detection |
| `nfc` | tag registration, unlock flow |
| `schedule` | alarms, `ScheduleAlarmReceiver`, timed modes |
| `widget` | `GuardianWidget` |
| `ui` | Compose screens, theme |
| `config` | import/export, `ConfigManager` |
| `build` | Gradle, SDK levels, signing, reproducibility |
| `release` | version bumps, fastlane metadata |
| `docs` | README, blog, policy pages |

Omit the scope only when a change is genuinely repo-wide.

**Subject** — imperative mood ("add", not "added"/"adds"), lowercase start, no trailing period,
≤50 chars (hard limit 72). It must complete the sentence *"Applying this commit will…"*.

**Body** — optional. Include it only when the *why* is not evident from the diff: the constraint,
the failure it fixes, the rejected alternative. Wrap at 72 columns. Never narrate the diff
file-by-file; the diff already does that.

**Footers**
- `BREAKING CHANGE: <consequence>` — required for any change to exported behavior, config schema
  (`ExportData.version`), or persisted state.
- `Refs #12` / `Closes #12` — issue links.
- `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` — required on AI-assisted commits.
  This trailer only. Do not put "Generated with Claude Code" banners in commit messages; that
  belongs in PR descriptions.

## Secret gate

This repo keeps signing material on disk. Before every commit:

```bash
git diff --staged --name-only | grep -Ei 'local\.properties|\.jks$|\.keystore$|google-services\.json' \
  && echo "BLOCKED: signing material staged" && exit 1
git diff --staged -U0 | grep -Ei '(KEYSTORE_PASSWORD|KEY_PASSWORD|KEY_ALIAS)\s*='
```

`.gitignore` covers these, but `git add -f` and new paths defeat it. If anything trips, stop and
tell the user — do not commit and clean up afterwards.

## Build gate

Gradle needs JDK 17+; `JAVA_HOME` on this machine may point at a removed JDK.
Prefix with `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk`.

| Changed | Required gate |
|---|---|
| `*.gradle.kts`, `libs.versions.toml`, manifest | `:domain:test :app:testDebugUnitTest` then `:app:assembleRelease` |
| Kotlin under `domain/src` or `app/src` | `:domain:test :app:testDebugUnitTest` |
| Docs, fastlane, README only | none |

The suite is 79 tests (six `:domain` classes plus `SanityTest`); expect 0 failures. Confirm the
count rather than trusting `UP-TO-DATE` — add `--rerun-tasks` if results look cached. Say which
gate you ran; never imply testing you did not do.

## Release hygiene

- `versionCode` must increase monotonically — Play rejects reuse. Bump it with `versionName`.
- Do not re-enable `dependenciesInfo` or the `ArtProfile` / `StartupProfile` / `VersionControlInfo`
  tasks. They are disabled for F-Droid/IzzyOnDroid reproducibility. If a change touches them, say so
  in the body and flag it to the user.
- Never commit build outputs (`*.apk`, `*.aab`) — they are ignored, and `-f` is not a workaround.

## Anti-patterns

Drawn from this repo's history. Do not reproduce them.

- Run-on subjects narrating the work: *"Added protection safe regime, where I think i got all the
  possible cheating solutions…"* → `feat(ui): require 2.5min hold to disable safe regime`
- Bare version subjects: *"version 1.1.7"* → `release: bump to 1.1.8 (versionCode 14)`
- Two changes joined with `+`: *"v1.1.6 + reprodusable builds (fix)"* → two commits.
- Uncertainty in the message: *"hope it will help, can't really test it"*. Confidence belongs in the
  PR or a comment, not the permanent log.
- Type without an object: *"fix"*, *"README update"* → name what changed.

## Examples

```
fix(service): keep force-close working on Android 16

killBackgroundProcesses no longer affects other packages at targetSdk 36,
so blocking now relies on the accessibility HOME redirect. The kill call is
retained as a no-op fallback for pre-36 devices.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

```
build: target Android 16 (API 36)

Play blocks updates below API 36 from 2026-11-01. Edge-to-edge and predictive
back need no migration; insets were already applied on every screen.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

## Stop conditions

Do not commit when: the diff contains unread or unrelated changes, the secret gate trips, the build
gate fails, or the user asked only for a message. Report the blocker instead.
