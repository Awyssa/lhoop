# AGENTS.md: working on this app

Guidance for anyone, human or AI agent, changing this repository.

This is **LHOOP**, the owner's own Android app for sleep and recovery from a WHOOP 5.0. It began as a
copy of [NOOP](https://github.com/ryanbr/noop) and keeps that app's Bluetooth, protocol, storage and
analytics code ("the core"). On 2026-10-03 everything else in the original app (the UI, widgets, the AI
coach, the importers, Oura, push, notifications, alarms and more) was removed. On 2026-10-06 the app
was renamed LHOOP throughout. What the app shows and how it scores is built new, on top of the core.

**Start with [`fork/docs/README.md`](fork/docs/README.md).** The project notes there hold the goals,
the decisions and their reasons, what is known about the WHOOP 5.0 and the scoring engine, the layout
of the app, the roadmap and the runbook. Update them when a decision is made or a finding changes.

## How to work here

- **Never run `git add`, `git commit` or `git push`.** The owner stages, commits and pushes himself.
  Make the change in the working tree, say which files changed, and stop. The same goes for anything
  else that writes to the index, the history or a remote: tags, stashes, resets, `git mv`, `git rm`.
  Reading is fine (`git status`, `git log`, `git diff`).
- **Do not install a build on the phone unless the owner asks.** See "The phone" below.
- **Pick sensible defaults** on small open questions and say what you chose. Keep reports short.
- **Say what is verified and what is assumed.** A build passing is not hardware working.

## The name

The app is LHOOP: `lhoop` in packages, file names and identifiers, `Lhoop` in class names, `LHOOP` in
prose. The old name must not come back. What still carries it, on purpose:

- `LICENSE` and `NOTICE`, and the licensor's name wherever it is cited. See "Hard limits".
- Web addresses, repository names, a contact address and a bundle id that belong to the original
  project or to other people. They are real addresses; renaming them would point nowhere.
- Unrelated words that merely contain the letters, such as `btsnoop` (Android's Bluetooth log) and
  test names with `NoOp` in them.
- The sentence above, and the one in the top-level `README.md`, that says where this code came from.
  The project notes call the original app "the original app" or "upstream", and its code "the core".

One file-format tag was shortened rather than renamed, to keep its header the same length:
`ImuSessionFileStore`'s magic is `LHOOPIM2`, eight bytes.

## Hard limits

- **No network.** The app has no network code and no `INTERNET` permission. Nothing leaves the phone
  except a backup the owner exports by hand. Adding a server, an account, telemetry, crash reporting or
  the permission back is out of scope unless the owner decides otherwise.
- **Personal data stays out of git.** Treat the repository as public. Raw strap data, database exports,
  backups (`.lhoopbak`, and older ones written under the old name), WHOOP exports, Garmin captures and
  anything derived from them are health data. They live in `whoop-data/`, which `.gitignore` excludes.
  Do not copy the shape of a real night (its times, durations or heart figures) into a test, a comment,
  a note or the demo seeder.
- **Clean-room interoperability.** No WHOOP firmware, decompiled app code, logos or assets, and no DRM
  circumvention. The app talks to hardware the owner owns.
- **Licence:** [PolyForm Noncommercial 1.0.0](LICENSE). The code taken from the original app is still
  licensed by its authors under those terms. `LICENSE`, `NOTICE` and the copyright notice must stay
  exactly as they are, old name included: the licence requires them to travel with the code. The app
  cannot be sold.

## The phone

The phone is where real loss can happen: it holds every recorded night, and a bad build costs nights.

- **The phone runs LHOOP** (`com.lhoop.whoop.staging`) since the evening of 2026-10-06. It holds every
  recorded night and it is the app connected to the strap. A build of this code installs over it when
  it is signed with the same key.
- **The app it replaced is gone.** The owner uninstalled it that evening, so LHOOP is the only app on
  the phone that talks to the strap. Keep it that way: a build with another package name installs
  beside LHOOP, reconnects by itself and takes records LHOOP then never gets. How the data came
  across, what was checked, and what a second app cost once, is under "Moving to the renamed app" in
  [`fork/docs/08-runbook.md`](fork/docs/08-runbook.md).
- **Rows LHOOP has synced exist only in LHOOP.** There is no tested way to carry them back into the old
  app, so a fault is fixed by installing a better build over LHOOP, not by going back.
- Before installing any build: export a backup from the running app, keep the last good APK to roll
  back to, and never uninstall to fix something (uninstalling wipes all data).

## Layout

Kotlin sources are under `android/app/src/main/java/`.

| Area | Path | What it is |
|---|---|---|
| Core | `com/lhoop/ble/`, `protocol/`, `data/`, `analytics/`, `LhoopApplication.kt`, `CrashCapture.kt` | The code taken from the original app. See "The core". |
| Other kept files | 15 small files under `com/lhoop/ui/`, `ingest/`, `testcentre/` (settings objects, units, the raw-sensor export, the test-centre switches) | Also from the original app. |
| Stand-ins | `fork/standins/` | They declare, in the core's packages, the symbols the core still expects from removed code. |
| The app | `fork/app/` | The driver that starts the core, the screens, and under `scoring/` how a night is found and scored. New work goes here. |
| Manifest overlay | `android/app/src/full/AndroidManifest.xml` | It only removes things from the main manifest. |
| Project tooling and notes | `fork/` | `fork/tools/` holds the morning routine: `pull_night.sh`, `night_report.py` and `capture_garmin.sh`. |

What each core layer holds: `protocol/` parses frames and decodes records (pure Kotlin); `ble/` is the
connection, bonding, history offload and the foreground `WhoopConnectionService`; `data/` is the Room
database and repository; `analytics/` computes HRV, sleep and recovery. The layout is described in
[`fork/docs/10-app-structure.md`](fork/docs/10-app-structure.md).

The core's names for its scores: recovery is **Charge**, the sleep score is **Rest**, strain is
**Effort**.

## The core

Until the rename, the core was kept byte-identical to upstream so that upstream's fixes could be merged.
That is over: every file's package changed, so git can no longer merge upstream, and the sync script was
removed. A fix from upstream has to be read there and applied here by hand. The last upstream commit
this code matched, apart from the cut and the rename, is `6ce65730`.

The core can now be edited. Edit it sparingly all the same:

- **The Bluetooth code cannot be tested without the strap, and it works.** Prefer new code under
  `fork/app/`, a stand-in, or a small change, over reworking `ble/`.
- **Do not change `WhoopDatabase` or its entities.** The schema must stay as it is, so a backup from
  the app on the phone still restores. Data of our own goes in a separate store: today that is
  `fork/app/SleepStore.kt`, a JSON file. Read the core's database freely; never write to it from
  `fork/app/` (the demo flavor's synthetic rows are the one exception).
- **A backup written under the old name must keep restoring.** `DataBackup` finds the database inside
  a backup by the end of its name, not by the app's name. `BackupRestoreNamesTest` guards that.

**Sleep comes from the strap's own state, not from the core's sessions.** The app reads the raw
`sleepStateSample` table and counts states 2 and 3 as sleep, less the strap's wake confirmation
(`fork/app/scoring/StrapSleep.kt`; the reasons are in `fork/docs/04-sleep-recovery-engine.md`). Do not
go back to `sleepSession` or its `sleepStateJSON` for what counts as sleep: the core's detector has
been wrong on real nights, and that stored copy is cut short on a strap worn round the clock. When the
rules for finding a sleep, its heart figures or its stages change, raise `SleepStore.RULES`.

**Deep and REM are estimates, and the screen says so.** They are the core's stager run over the
strap's night (`fork/app/scoring/SleepStagesCalc.kt`). Its deep sleep has been believable; its REM has
read about twice what it should. Do not present either as measured, and do not feed them into a score.

## Stand-ins

- **Settings code was extracted from the original app's removed screens.** `LhoopPrefs`,
  `ProfileStore`, `NotifPrefs` and `appLaunchIntent` keep the original defaults and bodies. The
  preference file and key names were renamed with the app, so they no longer match the original's.
  Never invent a key or a default. Each file names the upstream file and commit it came from.
- **Removed behaviours are no-ops** with the original signatures, returning the "feature is off" value.
  They must never throw.
- **`AppChangelog.CURRENT_VERSION` gates behaviour.** The core replays archived history frames once per
  version, keyed on it.

## The driver

`fork/app/FoundationDriver.kt` and `ScoringPass.kt` do what the original app's UI layer did to start the
core: the launch reconnect, the foreground service, the saved link settings and the backstop scoring
pass. It is a faithful copy of that behaviour, with line references in the files.

Anything that changes **what is sent to the strap, or when**, is a Bluetooth change: it needs the checks
in [`fork/docs/08-runbook.md`](fork/docs/08-runbook.md) on the real strap. Never add destructive
commands: firmware or DFU, ship mode, power cycling, force-trim, fuel-gauge reset, or anything else
that can brick, wipe or permanently alter the strap. Read
[`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) §BLE safety contract before touching this area.

## Build, test and what validates a change

Prerequisites: JDK 17+ (JDK 21 works), the Android SDK with platform 35, and `ANDROID_HOME` or
`android/local.properties`.

```bash
cd android
ANDROID_HOME=~/Library/Android/sdk ./gradlew testFullDebugUnitTest          # about 4,330 JVM tests
ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleFullRelease -PstagingRelease   # installable APK
```

If the test task stops at once with "property 'roomSchemas' specifies directory ... which doesn't
exist", run `./gradlew :app:kspFullDebugKotlin --rerun-tasks` and then the tests again. An incremental
build clears the schema folder Room generates; this happens after most source changes. After a large
change to the file tree, add `--no-build-cache --rerun-tasks` to the test task instead.

The APK is `com.lhoop.whoop.staging`. See "The phone" before installing it.

**The signing key is a file, and it is not in this repository.** Builds are signed with
`android/fork-debug.keystore` when it is present; `android/.gitignore` excludes `*.keystore`, so it
lives only on the owner's Mac. Without it Gradle signs with the machine's own debug key and says
nothing, and Android refuses to install such a build over an app signed with the real key. Before
installing a build from another checkout, compare its certificate with the app's
(`apksigner verify --print-certs`). The key itself is the original project's public one.

**Known failures that are not yours:**
- `RecoveryDriversTest.driverPointRoundingUsesNearestWithHalfTiesAwayFromZero` and
  `RecoveryDriversTest.issue51NegativeHalfTieUsesDefaultArg8WithoutChangingScoreOrDriverFields`. They
  failed the same way before any change here, on this Mac (JDK 21; upstream's CI runs JDK 17).
- Four `Tools/test_check_source_references.py` cases on macOS (a temp-folder path quirk).

Other checks: `python3 Tools/doc_comment_lint.py`, `python3 docs/protocol-examples/validate_examples.py`,
`python3 docs/protocol-examples/check_source_references.py`.

**Bluetooth behaviour cannot be unit-tested.** Compiling proves nothing about the connection. Say what
was checked on hardware and what was not.

## Rules that still apply to new code

- **Changing a score or deriving a signal: validate against varying input, not one match.** WHOOP
  motion and optical buffers hold a fixed number of samples per record, so spectral methods can
  manufacture a peak that looks physiological. One night that "matched" is not validation. Show the
  method tracks different nights and conditions, on held-out data.
- **Strap model resolution** goes through `DeviceFamily.forRegistryModel` (or `forRegistryDevice`),
  never a string compare. Reads use the registry's **active** strap id, not a raw Bluetooth address.
- **Do not show one fact twice** from two sources that can disagree. Resolve both from one function and
  one clock. The core's own figures are shown, but only inside the card that names them as the original
  engine's.
- **A diagnostic may only state what it observed.** Prefer naming a gap over a line that claims more.

## The repository, and handing work over

The repository's history starts on 2026-10-06, on branch `master`: the owner began a new repository
after the rename. Commits, commit ids and the `phone-…` tags from before that day do not exist here,
and the earlier repository is gone. Ids of the original project's own commits, such as `6ce65730`, can
still be looked up there.

The owner makes every commit. So when a piece of work is done:

- Say which files changed, one concern at a time. Comments and docs are in English.
- Check that nothing from `whoop-data/` and no personal figure is in the changes, and say so.
- State the verification: unit-test totals against the known failures, and what was or was not run on
  the phone.
