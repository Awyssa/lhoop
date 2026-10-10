# How the app is put together

Since 2026-10-03 the app is the original app's core with the owner's own app on top. This page is the
map: what was kept, what stands in for the removed parts, how the core gets started, and where the cut
build differs from upstream.

Sizes at the cut: main Kotlin went from 535 files and 203,631 lines to 289 files and 85,412 lines. Test
files went from 771 to 489.

## What was kept from upstream, untouched

All paths are under `android/app/src/main/java/com/lhoop/`.

| Part | Lines | What it does |
|---|---|---|
| `ble/` | 23,066 | Connection, bonding, history offload, keep-alive, reconnects, the foreground service |
| `protocol/` | 9,884 | Frame parsing and record decoding |
| `data/` | 10,478 | The Room database, DAOs, repository, backup export |
| `analytics/` | 35,826 | The core's health maths: HRV, sleep detection and staging, recovery, baselines |
| `LhoopApplication.kt`, `CrashCapture.kt` | 367 | Process start-up |
| 15 small files in `ui/`, `ingest/`, `testcentre/` | 2,645 | Settings objects, units, the raw-sensor export, the test-centre switches and IMU store |

Six Oura-only files inside `ble/` and `data/` were removed. Until the rename on 2026-10-06 everything
else there was byte-identical to upstream commit `6ce65730`. The rename changed the package of every
file, so that no longer holds; nothing else in them was changed, apart from one line in `DataBackup`
that lets a backup written under the old name restore.

`analytics/` is kept whole for now. Most of it (strain, workouts, stress, steps) is not needed for sleep
and recovery. It will be thinned once the app's own scoring exists.

## What was removed

The whole UI (`ui/`, 152 files), widgets, the AI coach, the importers (WHOOP CSV, Apple Health, Health
Connect, workout and nutrition files), Oura, self-hosted push, the update check, notifications, alarms,
location and GPS workouts, the Test Centre screens, Polar, and the tests for all of them.

With the AI coach, push and the update check gone, the app has no network code.

## Stand-ins

The core still names things from the removed code. `fork/standins/` declares them, in the core's
packages, so the core compiles without being edited.

| Kind | Files | Notes |
|---|---|---|
| Extractions | `ui/LhoopPrefs.kt` (1,339 lines), `ui/ProfileStore.kt`, `ui/NotifPrefs.kt`, `ui/AppLaunchIntent.kt`, `ui/AppChangelog.kt` | Settings code copied out of removed screens, then renamed with the app: the preference file and key names no longer match the original's. `LhoopPrefs` lost two members that needed a removed enum; `NotifPrefs` lost one. |
| No-ops | `notif/` (4), `widget/` (3), `alarm/` (3), `location/` (2), `ingest/HealthConnectWriter.kt`, `push/` (2), `polar/PolarModel.kt`, `ble/OuraLiveSource.kt` | Each returns the "feature is off" value. |
| Minimal types | `oura/` (3) | The smallest declarations the kept code needs. |
| The launcher | `ui/MainActivity.kt` | Keeps the original class name because the manifest and `appLaunchIntent` name it. It shows `fork.app.AppRoot`. |

26 files, 2,289 lines.

Two hazards:

- **A copy does not follow upstream.** If upstream changes a default in its settings object, nothing
  here fails.
- **`AppChangelog.CURRENT_VERSION` gates behaviour.** The core replays archived history frames once per
  version, keyed on it. The copy says `11.8.0` and must follow upstream's value.

## The driver

In upstream, the UI layer started the core. `ui/AppViewModel.kt` was the only caller of
`WhoopConnectionService.start`, the launch reconnect and the saved link settings. After the cut the app
built but never connected.

`fork/app/FoundationDriver.kt` and `ScoringPass.kt` reproduce what `AppViewModel` did as a consequence
of the app being opened, in the same order and with the same conditions and arguments. Line references
to upstream are in the files.

What connects the strap:

- **At launch:** if a strap was bonded before and "Keep connected in the background" is on (the
  default), the driver starts the foreground service and reconnects directly to the saved address.
- **In the background:** the foreground service keeps the process alive. Reconnecting after a drop, the
  30-second keep-alive and the 15-minute history offload all live in the core.
- **After a force-stop or an install:** nothing runs until the app is opened. This is upstream's
  behaviour, kept as it was.

Two things here are the app's own and not upstream's, both built on 2026-10-09. Both worked in an
emulator with a saved strap set by hand. On the phone the restart has been tried and worked; a
service taken away by Android has not happened there yet ([08-runbook.md](08-runbook.md)):

- **After a phone restart** the link comes back without the app being opened. `BootReceiver` takes
  Android's `BOOT_COMPLETED` and runs `StrapStartup.afterBoot`: the four steps of the driver that need
  no Activity (reconcile the source, re-arm the strap log, push the saved link settings, start the
  service and reconnect to the saved strap). Those four were moved out of the driver into
  `StrapStartup.kt` unchanged, and the driver calls them where their bodies were, so a restart drives
  the strap through exactly what a launch does. The broadcast only arrives once the phone has been
  unlocked. When the app is opened later the driver runs the same steps again, which is what already
  happens when the app is swiped away and reopened: the reconnect call returns at once on a live link.
- **Every time the app comes to the front** it starts the foreground service again if the service is
  not in the foreground, a strap is saved, the background setting is on and the link was not
  disconnected on purpose (`StrapStartup.ensureService`). It starts the service and nothing else: no
  connect call. This is for the day Android takes the service away while the process lives on, which
  opening the app did not undo before. To know "on purpose" the core's Bluetooth client gained one
  read-only line, `intentionallyDisconnected`; it is the only edit to `ble/`.

Scoring runs in the core after every offload. The driver's 30-minute pass is a backstop.

Settings cannot be changed in the app yet. The driver reads whatever the previous install saved.

## Where the cut build differs from upstream on the strap

- **The alarm slot is left alone.** Upstream re-evaluated the strap's alarm on every bond and once a
  day, which sent `DISABLE_ALARM` when no alarm was set. The cut build sends nothing to the alarm slot.
- **No live heart-rate requests from screens.** Upstream asked the strap for realtime heart rate while
  its Live, Health, Breathe or workout screens were open. None of those exist. Realtime capture is armed
  only by the saved "Continuous HRV capture" setting, which is off by default.

Everything else the UI layer sent or configured is reproduced.

## The screens

`fork/app/AppRoot.kt` holds three tabs, the night opened over them, and the place the driver is created
(above the tabs, so the core starts whichever tab is showing). The look is one dark palette in
`Theme.kt`, whatever the phone's setting. The screens were redrawn on 2026-10-06 from three drafts the
owner chose; before that there was one long screen of rows.

- **Today** (`TodayScreen.kt`). What the morning asks, in order: the app's own recovery as a ring, with
  a line on what drove it or why there is none; HRV, resting heart rate and the sleep score, each
  against the wearer's usual; time asleep against the need, with the sum the need came from; deep, REM
  and light; the night as a strip of asleep, restless and awake; any other sleeps; and the last seven
  days as small bars. "Last night" is the newest night, as long as it belongs to today by the core's
  clock (the day rolls over at 04:00). Otherwise the screen shows the newest night there is and says so.
- **The night** (`NightScreen.kt`), opened by tapping the recovery or the sleep card, or a day in
  Trends: recovery and the sleep score as two dials; the night and any sleep before it from noon to
  noon; heart rate through the night over the strip; where its HRV, resting heart rate and sleep sit
  among the nights it was compared with; and, folded away, how the sleep score was worked out (with
  the usual-sleep-need setting), the core's own figures for the day, and the other sleeps.
- **Trends** (`TrendsScreen.kt`): the last seven days to pick from; the last week against the four
  before it ("Progress"); sleep against need; HRV and resting heart rate against the usual; bed and
  wake times; and the week's numbers as a table.
- **Every screen has a pill** saying whether the strap is being recorded: "Synced" and when, or "Not
  connected", or "Background recording off" when the link is up and the service that holds it in the
  background is not in the foreground, or "Off the wrist" while the strap has reported itself off and
  not back on. It is there because the app was once down for a while with nothing on the home screen
  to show it.
- **Hours off the wrist** (`OffWrist.kt`, since 2026-10-09) come from the strap's own two events. The
  night screen draws them as a grey band on the noon-to-noon bar with a key under it, and says so on a
  night whose time in bed holds one. The home screen has one line under its header while the strap is
  off, or when last night left no night and the strap was off for an hour or more of it. Trends adds a
  line to a day with an hour or more off. The Strap tab has a "Worn" line and the hours off in the
  last 24 hours and 7 days. A stretch counts from ten minutes. A day is noon to noon, named by the
  date it ends on, everywhere.
- **While the recovery score leans on fewer than eight earlier nights** the home screen says how many,
  under the line on what drove it. The sleep card says the need once: the sum it came from shows only
  when debt or an earlier sleep is in it.
- `Insights.kt` holds the rules behind what these screens say and draw (pure, unit-tested), `Parts.kt`
  the pieces they are drawn from, `Nights.kt` and `NightsViewModel.kt` what they load. A missing value
  is a dash.
- **Strap** (`StatusScreen.kt`): connection, bond, model, firmware, battery, last sync, whether the
  service is running, and how many rows each signal has for the last 24 hours and 7 days. It has
  Connect, Disconnect, Sync now, Export backup, Import backup and a Debug logging switch. Import backup
  restores a backup through the core's `DataBackup.importFrom`, clears the app's own store of sleeps
  so they are rebuilt from the restored rows, and closes the app; it is disabled while connected.

## The widget and the icon

Both are the app's own, made on 2026-10-09 and on the phone since that afternoon, where the owner
placed the widget and it shows the home screen's figures.

- **The widget** (`MorningWidget.kt`, four cells by two) shows the newest night: its date, recovery
  with its word, time asleep, "Deep est.", "REM est." and HRV. It is a plain RemoteViews widget with
  an XML layout (`res/layout/morning_widget.xml`), chosen because it could not be looked at before
  going on the phone: each update is one call, and the layout's own texts show if no code has run.
  It draws only from a small file, `files/fork/morning.json` (`MorningNumbers.kt`), and never touches
  the Bluetooth client or the database, because Android can start the process just to draw it.
  `NightsReader` writes that file every time the nights are worked out, for the screens or not, so the
  widget and the home screen cannot disagree. While a widget is placed, the nights are worked out
  again whenever a sync finishes; Android also redraws it every half hour. When the stored night is
  not last night, every figure goes grey and it says "Nothing for last night yet": it always names the
  night by its date and never calls it "last night", since it can be looked at long after the numbers
  were worked out. What it says is decided in `MorningFace`, which is pure and unit-tested.
- **The icon** is the owner's own design, drawn on 2026-10-09: a thin white L on black, made of two
  bars that meet corner to corner. It is three vector files (`res/drawable/ic_launcher_*.xml`), traced
  from his picture so the bars sit where he drew them, and both launcher aliases in the manifest draw
  it. It replaced, the same day, a first icon the second assistant had chosen (a block L with a
  crescent). The original app's icon pictures were deleted.

## Where a night comes from

The rules, and why, are in [04-sleep-recovery-engine.md](04-sleep-recovery-engine.md). The pieces:

| File | What it does |
|---|---|
| `scoring/StrapSleep.kt` | Finds sleeps in the strap's own state, given one row per minute. Pure Kotlin. |
| `scoring/SleepDays.kt` | The stored record of a sleep, and which sleep is each day's night. Pure Kotlin. |
| `scoring/SleepVitalsCalc.kt` | HRV and resting heart rate for a sleep, by the core's own functions. |
| `scoring/SleepStagesCalc.kt` | Deep and REM for a sleep: the core's stager run over the strap's bounds. Estimates. |
| `scoring/SleepLog.kt` | Brings the stored sleeps up to date: keeps settled ones, works the rest out again. |
| `StrapSleepLoader.kt` | The reads: the strap's state and the heart rate by minute by SQL on the core's database (read-only); heart rate, beats and gravity through the repository. |
| `SleepStore.kt` | The app's own store: `files/fork/sleeps.json`, with a rules version. |
| `scoring/WhoopStyle.kt` | The app's sleep and recovery scores, fitted to how WHOOP scored the owner's history ([05-whoop-scoring-model.md](05-whoop-scoring-model.md)). |
| `AppSettings.kt` | The one setting: the usual sleep need, 8 hours until changed. |
| `NightsReader.kt` | The one reader for the process: nights, scores and the hours off the wrist. The screens and the widget both read through it, so they cannot disagree and the stored sleeps are never written from two places at once. |
| `OffWrist.kt` | The stretches the strap reported itself off the wrist, from its own `WRIST_OFF` and `WRIST_ON` events in the core's `event` table (read-only). Pure Kotlin. |

Nothing is written to the core's database. The store can be deleted at any time: every record is
worked out again from the strap's rows. The core still detects sleep and scores each day its own way;
`Nights.core` carries those figures to the screen unchanged.

## Seeing the UI without the phone

The `demo` flavor runs in an emulator, where there is no strap. Upstream's seeder writes 120 days of
synthetic stored days. `fork/app/DemoStrapNights.kt` adds made-up strap rows for the last 24 nights
(state, heart rate and beats every second, and gravity for the newest three so they get a stage
split), which is what the screens read. The newest night is shaped to show every optional row at once,
and the nights differ enough for the charts and the progress card to have something to show.

```bash
cd android && ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleDemoDebug
adb -s <the emulator's serial> install -r app/build/outputs/apk/demo/debug/app-demo-debug.apk
```

It installs as `com.lhoop.whoop.demo.debug`, beside the real app and with its own data. Seeding takes
under a minute on first launch, and the newest night appears last. `fork/tools/phone_ui.py` reads its
screens too.

The demo data also holds one made-up stretch off the wrist, early in the afternoon before the newest
night, so the grey band on that night's bar and the line on Trends can be looked at.

**The emulator may be in use.** The owner's other work shares the one AVD. Look first (`adb devices`,
`pgrep -fl qemu-system`) and never stop an emulator that was already running. A second instance of the
same AVD starts with `-read-only -port 5556` and answers as `emulator-5556`; what it changes is thrown
away when it stops. With the phone attached too, name the device on every `adb` command.

## The manifest

`android/app/src/full/AndroidManifest.xml` (and an identical copy for the `demo` flavor) merges over
the main manifest and only removes: ten
components whose classes are gone, six permissions nothing uses, the 24
Health Connect permissions and two package queries. The Bluetooth and foreground-service entries are
untouched. Until 2026-10-10 it also removed the `INTERNET` permission. It no longer does, because the
app has one use for the network: see "The server backup" below.

What the app adds of its own is declared once, in the main manifest beside the service: the boot
receiver (`fork.app.BootReceiver`, not exported) and the widget's receiver
(`fork.app.MorningWidgetReceiver`, exported, as a launcher needs). `ManifestReceiversTest` checks both
and that no overlay removes them. The widget's texts are in `res/values/morning_widget.xml`, apart
from `strings.xml`, which is the original app's catalogue and is held to its translations by a test.

## The server backup

Written on 2026-10-10, not on the phone. The app can send its data to a server the owner runs, so
that a lost phone no longer loses the nights. The design, the server and what was tested are in
[12-server-backup.md](12-server-backup.md); the server's code is `fork/server/`.

In the app it is `fork/app/backup/`: the hour checksum both sides share, the delta files, the HTTP
client, a daily job (Android's WorkManager, while charging on Wi-Fi) and a section on the Strap tab
where the owner gives the server's address and token and can press Back up now. It reads the core's
database through Room's own connection and has no way to write to it. It is off until set up, and
HTTPS only.

`BackupServer.kt` is the one file in the app that opens a connection; `NetworkUseTest` fails the
build if another does. The network policy (`res/xml/network_security_config.xml`) refuses plain HTTP.
Debug builds have their own copy that allows it to `10.0.2.2` alone, the Mac as an emulator sees it,
so the upload can be tried against a server there.

The Import button also changed that day: a backup whose database is over 2 GiB used to be refused,
and now the app asks and restores it on a yes.

## Status

**The build of 2026-10-09** (the service coming back on opening and after a restart, the hours off
the wrist, the baseline line, the widget, the icon) is built and unit-tested on the Mac: 4,410 tests,
with only the two known failures, and a release APK with the right certificate. It is on the phone
since 14:27 that day, where it connected, bonded, synced and exported, and kept a Disconnect down
([08-runbook.md](08-runbook.md)). Before that it was rehearsed in an emulator, on Android 17 as on the phone: every screen on
made-up data and on the newest real backup, the icon, the widget placed and redrawn, the service
brought back by reopening the app after Android took it away, a Disconnect that stayed down, and the
service starting by itself after a restart ([08-runbook.md](08-runbook.md), "What the rehearsal
showed"). On the phone the same afternoon the owner placed the widget and restarted the phone: the
link came back without the app being opened and nothing was lost. Not yet done there: a night.

**The build before it** (2026-10-06) was built and unit-tested with 4,359 tests. The three redrawn
screens were looked at in an emulator on made-up data on 2026-10-06, each from top to bottom, with a
night open and its folded sections open; then in an emulator on the newest real backup; then on the
phone, where the build went on at 23:43 that night and each screen was opened once. That covered the
state before a first recovery score. They have not yet been lived with: no morning has been read on
them, and Trends has three nights to show. On the phone, connection, bond, sync and export are
confirmed on the strap, and three nights have recorded completely. The checks to run are in
[08-runbook.md](08-runbook.md).
