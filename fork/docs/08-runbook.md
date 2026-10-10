# Runbook

How to do the recurring things. Commands run from the repository root unless stated.

**The app was renamed LHOOP on 2026-10-06, and that changed what a build is to the phone.** Read this
first:

- **What the phone runs** is LHOOP, `com.lhoop.whoop.staging`, since the evening of 2026-10-06. It
  holds every recorded night and it is the app connected to the strap. The build on it since
  2026-10-09 at 16:58 is `lhoop-2026-10-09-icon.apk` (sha256 `d2d291425a4f…`): the third LHOOP build
  with the owner's own icon, three icon files apart from `lhoop-2026-10-09-ready.apk` (sha256
  `c30bb30ba8bb…`), which was on the phone from 14:27 to then and is the one to go back to. Before
  those: `lhoop-2026-10-06-screens.apk` (6 October, 23:43, to 9 October, 14:27) and
  `lhoop-2026-10-06-first.apk`.
- **A build of this code installs over the app on the phone**, as long as it is signed with the same
  key. See "Install on the phone".
- **The app it replaced is gone.** The owner uninstalled it on the evening of 2026-10-06. LHOOP is the
  only app on the phone that talks to the strap. Never install a build with the old package name
  beside it: two apps must not both hold the strap (see "Moving to the renamed app" below for what
  that cost once).
- **There is no tested way back that keeps what LHOOP records.** The old app and the original build
  (the unmodified original app, build 550 from upstream `c800fb61`) are kept as APKs, and both carry
  the old package name, so either would install as a second, empty app. The old app has no Import
  button. The original build has a restore screen, but it looks inside a backup for the database under
  the old name, so it would not take a LHOOP backup as it is; that route has not been tried. A sync is
  acknowledged to the strap, which then does not hand those records out again, so rows synced by LHOOP
  exist only in LHOOP. A fault in LHOOP is best fixed by installing a better LHOOP over it.

## Build

Needs JDK 17 or newer (21 works) and the Android SDK.

```bash
cd android
ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleFullRelease -PstagingRelease
```

The APK is `android/app/build/outputs/apk/full/release/app-full-release.apk`.

## Moving to the renamed app

**Done on 2026-10-06, between 19:25 and 19:28.** A renamed build is a different app to Android and
starts empty, so the data came across through a backup. In order, each step checked before the next:

1. **Old app:** its sync finished, then **Disconnect** ("not connected"), then **Export backup**. The
   export was pulled to the Mac and checked: `PRAGMA integrity_check`, and a newest row less than a
   minute old.
2. **The old app was stopped** (`adb shell am force-stop <the old package name>`). Not uninstalled.
3. **LHOOP:** the nearby-devices and notification permissions were granted over USB
   (`adb shell pm grant`), with the owner's agreement, rather than by tapping Allow.
4. **LHOOP:** Strap tab → **Import backup** → Choose the file → the export. "Backup restored", the
   app closed, and on opening it again the Last night tab showed the nights.
5. **LHOOP:** Strap tab → **Connect**. "connected" and "Bonded: yes" inside half a minute, then a
   sync.

What was checked afterwards, on an export taken from LHOOP ten minutes later:

- **The restore lost nothing.** Every table with a time column has the same number of rows, up to the
  old app's last one, as the old app's export.
- **The move lost nothing.** The strap went on recording while neither app held it, and LHOOP's first
  sync brought those minutes in: heart rate, sleep state, gravity and skin temperature have a row for
  every second across the move.
- **The Last night tab reads the same** as the old app's did that afternoon, figure for figure, in the
  app's own cards and in the original engine's card. The core scored the recent days again after the
  restore, because the old rows are stored under an id that ended in the old name, and for the night
  on screen it arrived at the same figures. It stored four sleep sessions where the old app had five;
  the app does not read them.
- Hardware checks 1, 2, 3, 4 and 9 passed on LHOOP: see the next section but one.

What a backup carries: the database, and the profile settings. What it does not: the other settings
(the saved strap, Debug logging and the rest live in preference files whose names changed with the app,
so LHOOP started on the defaults and learned the strap when it first connected), and the app's own
sleep records, which LHOOP works out again from the strap's rows.

Left to do:

- **A first full night under LHOOP** (check 8), and the link holding in the background (check 5).

Battery usage for LHOOP was set to Unrestricted by the owner at 22:29 that evening (see "Phone
settings" for what it took).

The old app was removed by the owner later the same evening, after the checks above and the following.

**What two apps on one strap cost, seen on 2026-10-06.** The old app was still labelled with the old
name and had the same picture as LHOOP, and it was opened by mistake that evening. It reconnected at
once. Android shares one Bluetooth link between apps, so for 26 minutes both were connected, each
with its own service, each asking the strap for history. The strap hands a record out once: what the
old app fetched (24 minutes when it opened, and two more after a reconnect) never reached LHOOP, and
LHOOP's recording has a hole there. Those records went with the old app when it was uninstalled.
`adb shell pidof <package>` for each app shows whether two are running.

After the uninstall LHOOP was checked again: still the same process, connected and bonded, syncing on
demand, the nights unchanged, and every second of the last 24 hours present but for that hole.

If LHOOP ever cannot bond, the strap has to be put into pairing mode again (see "Pairing a WHOOP 5.0").

## Install on the phone

This section is about installing a newer build over the same app. The phone is where real loss can
happen: a build that fails to connect loses nights. So:

1. **Export a backup from the app that is running** and pull it off the phone (see below).
2. **Keep the APK that is on the phone** to roll back to.
3. Install over the top:

   ```bash
   adb install -r android/app/build/outputs/apk/full/release/app-full-release.apk
   ```

4. Run the hardware checks below.
5. **Keep the APK**, named by date, outside the repository, and note in these pages which build is on
   the phone. Commits and tags are the owner's to make.

Rules:

- **Never uninstall to fix something.** Uninstalling deletes all data: the app opts out of Android
  backups. Install over it.
- Android refuses an install whose version code is lower than the one on the phone. Both builds are 550.
- If more than one device is attached (an emulator counts), pick the phone: run `adb devices`, then
  `export ANDROID_SERIAL=<serial>`.
- **The signing key is `android/fork-debug.keystore`, and it is not in the repository** (the ignore
  file excludes `*.keystore`). It is on the owner's Mac. A build made without it is signed with that
  machine's own debug key, without any warning, and Android refuses to install it over the app. Check
  before installing: `apksigner verify --print-certs <apk>` must show the same certificate as the last
  good APK. The key is the original project's public one, so install only APKs built here.

To roll back, install the previous APK the same way.

## Hardware checks for a build that changes strap behaviour

### The build of 9 October: on the phone since 14:27 that day

**Installed on 2026-10-09 at 14:27**, over the build of 6 October, after a backup was exported from
the running app and checked (whole, and complete up to the minute). What was checked on the phone and
the strap straight afterwards:

- Checks 1 to 4: it installed, and once opened it was connected, bonded and synced within seconds,
  with "Background service: running". That line now means in the foreground, and it read so on the
  phone as it had in the emulator. The battery setting carried over.
- The screens show what the rehearsal showed, on the phone's own data: the baseline line under the
  recovery score, the need said once, the off-wrist line on Trends, the grey band and its key on a
  night's bar, and the "Worn" line and the off-wrist row on the Strap tab. No crash.
- Check 7 from the Strap tab: after Disconnect the service was gone and the app stayed down through
  two reopenings; Connect had it connected, bonded and synced six seconds later.
- Check 9: an export from the new build worked, and it shows no hole across the install or the
  Disconnect test: every second of the hour around them is there.
- Two and a half minutes in the background left it connected with its service in the foreground.

**A restart, the same afternoon** (the owner restarted the phone at 14:38, unlocked it and did not
open the app; read over USB nine minutes later):

- The app's process had been started by Android at 14:40:56, for the widget's redraw, and one second
  later the strap service was started from a receiver and allowed. The reason Android logged on the
  phone was `SYSTEM_ALLOW_LISTED`, the battery setting "Unrestricted"; in the emulator, without that
  setting, it had been `BOOT_COMPLETED`. Either is enough by itself.
- With the app's screen never opened since the restart, the service was in the foreground and the
  link was up (the log showed it reading the strap's signal and battery). The lines of the reconnect
  itself had already rolled out of the phone's log.
- Opened then: connected, bonded, synced, under the same process the restart had started.
- An export afterwards has every second of the half hour around the restart.
- The widget, placed by the owner, shows the home screen's figures.

**Still to do, by the owner:** a Disconnect from the notification (the second half of step 5) and a
night unopened (step 7).

What follows is the plan as it was written before the install.

This build changes **when** the strap service starts and when the launch sequence runs. It sends
nothing new to the strap: no new command, no new way of connecting. What is new
([10-app-structure.md](10-app-structure.md), "The driver"):

- Opening the app starts the foreground service again if Android took it away while the process
  lived on, unless the link was disconnected on purpose.
- After a phone restart the app reconnects by itself once the phone has been unlocked, without being
  opened.
- A home-screen widget, the hours off the wrist on every screen, a line under the recovery score
  while its baseline is short, and the app's own icon.

It was verified by reading, 4,410 unit tests (the two known failures only) and a release build with
the right certificate and both new components in its manifest. When it was built the emulator was
in use; it was rehearsed there later the same day, on Android 17, the phone's version, in a
throwaway read-only instance, before it went on the phone.

**What the rehearsal showed** (the demo build on made-up data, then this release build with the
newest real backup restored into it):

- Every screen opens with the new parts in place: the grey band and its key on the noon-to-noon bar,
  the line on Trends for a day with an hour or more off the wrist and none for a day with less, the
  "Worn" line and the off-wrist row on the Strap tab, "Based on your last 5 nights. A full score uses
  8." under the recovery line, and the sleep card saying the need once when the usual need is set as
  it is on the phone. The figures on the restored data are the Mac replay's. No crash at any point.
- The icon draws as designed, in the launcher and in the widget list.
- The widget was placed from the launcher's list and drew at once, the same figures as the home
  screen; a tap opens the app. With an older night stored and Android restarted, the system's own
  redraw greyed every figure and said "Nothing for last night yet".
- **The incident of 6 October was recreated** (background use restricted while the app was in the
  background): Android took the service out of the foreground within three seconds and stopped it
  within about a minute, and the process lived on. Reopening the app, with no tap, put the service
  back in the foreground under the same process id. The same held when the service had only been
  demoted. Before this build that needed a forced stop.
- A Disconnect from the notification stayed down through two reopenings; Connect brought the service
  back.
- **After a restart** of the emulator's Android, with a saved strap set by hand and the app not
  opened, the service was in the foreground within twenty seconds. Android's own log gives the reason
  it allowed the start as `BOOT_COMPLETED`. Opening the app afterwards kept the one process.

So the three things that were assumed (the restart broadcast arrives, Android lets the service start
from it, and a demoted service reads as not in the foreground) hold on an emulator of the phone's
Android version. What the rehearsal could not show: anything with the strap itself, since an
emulator has none; the phone's own build of Android, which is not the emulator's; and a restart on a
phone that has a screen lock. The first two were then covered by the install (above); the restart is
still to be tried.

Before installing: export a backup and pull it (`fork/tools/pull_night.sh`), and keep
`lhoop-2026-10-06-screens.apk` to go back to. Then, in this order:

1. **Checks 1 to 4 below**, as for any build. On the Strap tab, "Background service" must read
   "running": that line now means running in the foreground. It read so in the emulator; "not
   running" straight after a connect on the phone would mean the phone differs, not that recording
   has stopped. Tell the assistant before doing anything else in that case.
2. **The screens, once each.** Today: the recovery card says "Based on your last N nights. A full
   score uses 8." while N is under 8, and the sleep card says the need once when there is no debt.
   A night that has hours off the wrist between its two noons shows a grey band on "Noon to noon" and
   a key under it (`whoop-data/NOTES.md` says which night has one). Trends: that day's
   summary says "Off the wrist for …, noon to noon." Strap: a "Worn" line, and "Off the wrist" as the
   last row of the table.
3. **The icon** in the launcher: a blue block and a grey block making an L, with a crescent. (That
   icon was replaced the same afternoon by the owner's own, a thin white L on black, in a build kept
   as `lhoop-2026-10-09-icon.apk`: the same code with three icon files changed. It went on the phone
   at 16:58 after a checked backup, and reconnected, bonded and synced when opened.)
4. **The widget.** Long-press the home screen, Widgets, LHOOP, and place it. It should show last
   night's date, recovery, time asleep, "Deep est.", "REM est." and HRV, the same figures as Today.
   Tapping it opens the app. If it says "Can't load widget", remove it and tell the assistant.
5. **Check 7 both ways**, because Disconnect must still stay down. Disconnect on the Strap tab, press
   Home, open the app again: still down, no notification. Connect. Then Disconnect from the
   notification, open the app: still down. Connect. Last: Disconnect, tap Import backup, cancel the
   file picker: still down. Connect.
6. **A restart.** Restart the phone, unlock it, and do not open LHOOP. Within two minutes the strap
   notification should appear. Then open the app: connected, bonded, "Synced" a moment ago. With the
   phone on USB the log should show "Auto-reconnecting to your saved WHOOP 5.0 / MG…" once, before the
   app was opened. If no notification appears, opening the app brings everything back as before; the
   phone then differs from the emulator, where this worked.
7. **A night**, unopened: check 8.
8. **Optional, and it costs a gap that the next sync fills: the incident itself.** Settings → Apps →
   LHOOP → App battery usage: flip "Allow background usage" off and on, choose Unrestricted again,
   return to the app. Without a tap the Strap tab should read "Background service: running" and the
   notification should be back, under the same process id. Until this build that needed a forced stop.
9. **Optional:** restart with Bluetooth off, unlock, then turn Bluetooth on: it should connect unopened.

If any of 1, 5 or 6 goes wrong in a way that leaves the strap unconnected, install
`lhoop-2026-10-06-screens.apk` over it: the data is untouched either way.

Results for the build of 3 October 2026: checks 1, 2, 3, 4, 7 and 9 passed on 2026-10-03, and
checks 5 and 8 on the first night (3 to 4 October): 16 hours in the background and a complete night.
Checks 6 and 10 have not been run. The strap used 4% of its battery over that night.

The builds of 4 and 6 October (two that day) changed nothing in how the app talks to the strap. Each
was installed over the last, started with `adb shell monkey -p <package> -c
android.intent.category.LAUNCHER 1` while the phone was locked, and seen in the log to reconnect and
finish a sync (checks 1, 2 and 4). All of those were builds from before the rename.

The second LHOOP build (6 October, 23:22: the redrawn screens, the nap rule, deep and REM) changed
nothing in how the app talks to the strap. Before it went on the phone it was installed empty in an
emulator and given the newest real backup through Import backup: it opened the real nights on all
three screens with no error. On the phone a backup was exported and checked first, then the build was
installed over the app at 23:43. It reconnected by itself a second after the install and synced
(checks 1 to 4); its screens showed the same night as the replay on the Mac; its battery setting
carried over; and two and a half minutes in the background left it connected with its service running.

That build was looked at again on the morning of 2026-10-09, after three nights. It was still the
process started by the install, 2 days and 7 hours earlier, with its service in the foreground and
its battery setting unchanged; Android recorded no exit of it. So on LHOOP:

- 5 passed: connected and syncing in the background for the whole of that time, with no tap.
- The out-of-range half of 6 passed: the link was down for hours three times and came back by itself
  ([03-whoop5-status.md](03-whoop5-status.md)). The Bluetooth toggle has still not been tried.
- 8 passed three times: each night is whole, to the second, and matches the Mac's replay.
- 10: since its last full charge the phone had spent under 1% of the power it used on LHOOP. The
  strap's own drain is in [03-whoop5-status.md](03-whoop5-status.md).
- Still not run on LHOOP: the Bluetooth half of 6, and the Disconnect half of 7.

The one hole in those three days was the strap off the wrist, which is not a fault (same page).

**LHOOP** (the build of 6 October, 18:48) changed nothing in how the app talks to the strap either, but
it is a new install with fresh settings, so the checks were run again after the move on 2026-10-06:

- 1, 3, 4 and 9 passed, and the Connect half of 7.
- 2 passed after a forced stop: opened again with no tap, the log read "Auto-reconnecting to your saved
  WHOOP 5.0 / MG…", and it was connected, bonded and syncing within fifteen seconds.
- 6 was not run, but one recovery was seen: the link timed out (status 147) nine minutes after the
  move, and the app reconnected by itself on its third try, under a minute later, and synced. The
  strap's own event log shows the old app losing and regaining the link several times a day in the
  same way.
- Not run on LHOOP yet: 5, 6, the Disconnect half of 7, 8 and 10.
- R-R intervals come only out of the strap's history records, and the strap leaves them out while
  the wrist is moving. None arrived in the first quarter of an hour under LHOOP, with the wrist moving
  throughout; the sync at 19:56 then brought in several hundred. So an R-R count that stands still
  for a while is not a fault by itself.
- In the background "Last sync" moves about every half hour, not every quarter. The timer for the
  automatic sync and the shortest gap allowed between two automatic syncs are both 15 minutes, and
  the timer came two seconds early: the log read "Backfill: skipped (PERIODIC) - policy floor not met"
  once, at 19:53. By the code every other tick goes that way. It is the original app's behaviour and
  loses nothing, because the strap keeps its records until they are fetched. Opening the app or
  tapping Sync now syncs at once.

| # | Check | What to look for |
|---|---|---|
| 1 | It installs | `Success`. A signature or downgrade error means stop. |
| 2 | It connects by itself | Open the app and tap nothing. Within about a minute: "Connection: connected", "Background service: running", and the ongoing notification. The 7-day counts are non-zero straight away, which shows the existing database opened. |
| 3 | It is bonded | The line reads exactly "Bonded: yes", and firmware and battery fill in. "yes (live heart rate only, no encrypted bond)" means the handshake did not complete. |
| 4 | It offloads | Tap Sync now. "connected, syncing history (N chunks)", then a fresh "Last sync" and higher 24-hour counts for heart rate, gravity, skin temperature, sleep state and R-R `WHOOP5_HISTORICAL`. |
| 5 | The link survives the background | Press Home, lock the phone for 45 minutes, reopen. The notification is still there, it is still connected, and "Last sync" moved without a tap. Repeat after swiping the app away. |
| 6 | It recovers | Toggle Bluetooth off and on; walk out of range and back. Each time it reconnects without tapping Connect. |
| 7 | The buttons work | Disconnect: the notification goes and it stays down. Connect: back to connected and bonded. |
| 8 | A night is recorded | Do not open the app overnight. In the morning: a recent "Last sync", tens of thousands of new heart-rate rows, and non-zero gravity, skin temperature, sleep state and R-R. |
| 9 | Export works | Export backup proposes `lhoop-backup-<date>.lhoopbak` and says "Backup exported." The file is a zip holding `lhoop-backup.sqlite`. (A build from before the rename used its old name in all three.) |
| 10 | Battery | Strap and phone drain over a night is about what it was on the original build. |

Until the build of 9 October, nothing recorded after a phone restart or a force-stop until the app
was opened once; that is upstream's behaviour. From that build, which the phone runs, a restart
brings the link back by itself once the phone has been unlocked (seen on the phone on 2026-10-09,
and nothing was lost across it). A force-stop still needs the app opened: Android delivers nothing to an app it was
told to stop. So does an install.

Installing the cut build over the original silently drops anything the original had set up outside the
core: placed widgets, wrist notifications, a phone alarm or wind-down reminder, daily auto-backup and
Health Connect access.

### The build with the server backup: not on the phone

Written on 2026-10-10. It changes nothing in how the app talks to the strap, but it is the first
build with the `INTERNET` permission, so it gets its own list.

**Rehearsed on 2026-10-10** in emulators on the phone's Android version, with the demo build and a
server on the Mac ([12-server-backup.md](12-server-backup.md), "What steps 0 and 2 showed"): the
first backup, a repeat, a restore from the server's export, a run after that restore, a wrong token,
the server down, and a backup with a database over 2 GiB restored after the app asked. No crash.

**Before it goes on the phone:** the server has to be running on the owner's machine with its name
and certificate, because the release build is HTTPS only and cannot be tried against the Mac.

**To check on the phone, when the owner asks for the install** (after an export, with the last APK
kept, as for any build):

1. **Checks 1 to 4 below**, as for any build: connected, bonded, synced, "Background service:
   running". Nothing about the strap should differ.
2. **Before a server is set up:** the Strap tab says "Server backup: not set up", and nothing else
   has changed.
3. **Set up:** the owner types the address and pastes the token. A wrong address is refused with its
   reason. On Wi-Fi and charging the first run starts by itself; otherwise Back up now.
4. **The first run finishes** and the line reads `complete` with the time. On the server,
   `lhoop-backup status` shows the tables and `verify` passes. With six days of data the first run
   sends about 30 MB.
5. **The strap is not disturbed by it:** a sync during or straight after the run still lands, and
   the next morning's pull has no hole around the time of the run.
6. **Back up now again** sends almost nothing, in a few seconds.
7. **The next morning**, without touching the app: the line shows a time from the night or early
   morning, if the phone charged on Wi-Fi.
8. **Mobile data:** with Wi-Fi off, the daily run must not happen. Back up now may, since the owner
   asked.
9. **Battery:** nothing unusual in Android's battery page for the app after a day.

Then two weeks beside the morning pull, comparing the server's export with the phone's own, before
the server is trusted as the backup.

## Phone settings

- Settings → Apps → LHOOP → App battery usage → **Unrestricted**. Set for LHOOP on 2026-10-06. Android
  does not carry it over from one app to another, so a build under a new package name needs it set
  again. `adb shell dumpsys deviceidle whitelist` lists the apps that have it.
- **How to set it, and the trap.** That page shows a switch, "Allow background usage". Leave the
  switch on and tap the words beside it: the next page offers Optimized and Unrestricted. Turning the
  switch off restricts the app instead, even for a second. On 2026-10-06 it was flipped off and on,
  twice: each time Android took the strap service out of the foreground at once and froze the app
  ten seconds later, and the first time it stopped the service after a minute. Switching back on did
  not undo any of it, and nor did choosing Unrestricted straight afterwards.
- **After that, the build of 6 October had to be started again**: stop it and open it
  (`adb shell am force-stop`, then launch), or tap Connect on the Strap tab. Opening it was not enough
  on that build: it started its strap service when its process started and when Connect was tapped,
  not when it was brought to the front. "Background service: not running" on the Strap tab is the sign.
  What it missed while down was fetched from the strap on reconnecting both times; nothing was lost.
  With Unrestricted set and the app started again, it stayed connected in the background with its
  service in the foreground (checked for the first few minutes; the long check is number 5 under
  "Hardware checks").
- **From the build of 9 October, which the phone runs, opening the app is enough**: every time it
  comes to the front it starts the service again if the service is not in the foreground and the link
  was not disconnected on purpose. Built for exactly this, and it worked when the incident was
  recreated in the emulator; the incident itself has not been repeated on the phone.
- Open the app once after every app update.

## Working on the phone over USB

- The phone locks after 60 seconds and cannot be unlocked over adb. Anything that reads or taps the
  screen needs the owner to unlock it and keep it awake.
- `adb shell uiautomator dump` reads what is on screen as text, which is enough to find a button by its
  label and to read the Strap tab. Only read the app's own screens: a dump of whatever is in front
  also shows other apps.
- With **Debug logging** on, `adb logcat -s WhoopBleClient` shows each sync. A good one ends with
  `Backfill: session ended — reason=HISTORY_COMPLETE` and `Backfill: post-sync scoring pass done`.

## Pairing a WHOOP 5.0

The strap is already paired with this phone, and the pairing survives installing a new build over the
old one. If it ever has to be done again:

1. Charge the strap fully.
2. Make sure no other phone, and not the WHOOP app, is connected to it.
3. Put it on, then tap the band firmly and repeatedly until the lights flash blue.
4. Original build: choose **WHOOP 5.0 / MG** and connect. Cut build: tap **Connect**. Accept Android's
   pairing prompt.
5. Success is "Bonded: yes". Live heart rate only means it did not pair fully: repeat steps 3 and 4.

The cut build has no device picker. It looks for the strap family it last saw. Pairing a strap from
scratch has not been tried on it.

To see what Android thinks:

```bash
adb shell dumpsys bluetooth_manager | grep -i whoop
```

A paired strap shows `le_linkkey_known:T` and `le_encrypted:T`.

## Watching the strap log

Turn on **Debug logging** (cut build: the switch on the status screen; original build: More → Test
Centre). Then:

```bash
adb logcat -T 1 -v threadtime | grep --line-buffered -iE "WhoopBleClient|BluetoothGatt|BondStateMachine"
```

The app's own lines carry the tag `WhoopBleClient`. Without Debug logging only Android's Bluetooth lines
appear.

## The morning routine

With the phone plugged in, unlocked and awake:

```bash
fork/tools/pull_night.sh
```

```bash
fork/tools/night_report.py whoop-data/lhoop-backups/night-$(date +%Y-%m-%d) --timeline
```

The first exports a backup through the app's Strap tab and pulls it into `whoop-data/lhoop-backups/`.
It ran against LHOOP on the phone on the evening of 6 October, after the move, and worked. To drive a
build installed under another package name, set `APP_PACKAGE` (`adb shell pm list packages whoop`
prints the names). Both tools read a backup whatever the app that wrote it was called. The second
prints how complete the
recording is, the sleeps in the strap's own state by the app's rules, with heart rate and HRV worked
out independently from the raw data, the core's sessions beside them, and what the core stored. Add
`--hours 90` to see several nights. Hours the strap reported itself off the wrist are listed as that,
and a gap in the samples is called a gap only when the strap was on.

Then compare with the app: its Today tab should show the same time in bed, time asleep and awake
time as the report's newest night, HRV within a millisecond or so, and resting heart rate within a beat
(the report uses simpler arithmetic for those two on purpose). Note the app's deep and REM beside the
Garmin's: they are the figures least to be trusted, REM above all. To replay the app's own code over the
backup on the Mac:

```bash
cd android && ANDROID_HOME=~/Library/Android/sdk ./gradlew testFullDebugUnitTest --tests "fork.app.StrapSleepBackupCheckTest"
```

Its output is in `android/app/build/test-results/testFullDebugUnitTest/TEST-fork.app.StrapSleepBackupCheckTest.xml`:
each night as the app finds it, with its deep and REM, then the scores the screen shows for it, beside
what they would be with the other sleeps left out.

For the Garmin, the owner opens last night's sleep page in Garmin Connect (More → Health Stats →
Sleep) and leaves it on screen. Then:

```bash
fork/tools/capture_garmin.sh
```

It saves a screenshot and the text of each screen of the page into `whoop-data/garmin/<date>/`,
scrolling until the page ends, then the Stages view of the same page. It captures nothing unless
Garmin Connect is in front. Garmin's home screen only gives resting heart rate.

The text holds the score, time asleep, stage totals, heart rate, HRV and breathing. When the sleep
started and ended, and when the wearer was awake, are only in the timeline picture: read them off the
screenshot. For another night, tap the arrow beside the date on the page and run the script again with
a name, for example `fork/tools/capture_garmin.sh night-before`.

The scrolling part ran on the real page on 6 October. The Stages part was done by hand that day; the
script's version of it has not run yet.

With the Stages view captured, the stage split can be put beside the Garmin's:

```bash
fork/tools/stage_whatif.py whoop-data/lhoop-backups/night-$(date +%Y-%m-%d) --garmin whoop-data/garmin
```

It prints the app's deep, REM and light for every sleep in the backup (they must equal what the app
and the replay test show), what REM would be with one term of the stager changed, and the Garmin's
split for the nights that have a capture. The replay test prints the same comparison from the app's
own code. The Stages capture each morning is what the REM work is waiting on
([06-validation-plan.md](06-validation-plan.md), "What a stage fix needs").

## Getting a night off the phone

- **Cut build:** tap **Export backup** on the status screen and save into Downloads.
- **Original build:** Settings → Backup & restore → **Export**.

The file is `lhoop-backup-<date>.lhoopbak`, an unencrypted zip holding `lhoop-backup.sqlite`. Android's
file picker saves it as `lhoop-backup-<date>.lhoopbak.zip`, and adds ` (1)` when the name is taken. A
build from before the rename uses its old name in the same places. Then:

```bash
adb shell ls /sdcard/Download/ | grep -- -backup-
```

```bash
adb pull /sdcard/Download/<file> whoop-data/
```

The cut build has no button for the raw sensor CSV export yet; the code for it is kept.

Everything pulled is health data. Keep it in `whoop-data/` or outside the repository.

## WHOOP history

The saved API downloads are in `whoop-data/`. To re-run the analysis:

```bash
python3 fork/whoop-history/whoop_history.py --personal
```

To add nights, save more responses from the browser into `whoop-data/` under any file name. Overlaps
are fine. See [05-whoop-scoring-model.md](05-whoop-scoring-model.md).

## Upstream's changes

They can no longer be merged. Until 2026-10-06 the core was kept byte-identical to upstream so that its
Bluetooth fixes could be merged in. Renaming the app changed the package of every file, so git sees no
file in common any more, and the sync script was deleted with the rename. Its list of removed paths,
`fork/removed-paths.txt`, is kept: the check of the documents' source references reads it.

A fix from upstream now has to be read there and applied here by hand. The last upstream commit this
code matched, apart from the cut and the rename, is `6ce65730`.

## Tests

```bash
cd android && ANDROID_HOME=~/Library/Android/sdk ./gradlew testFullDebugUnitTest
```

Expect about 4,320 tests with two failures in `RecoveryDriversTest` that also fail on untouched
upstream, and six skipped. The list of known failures is in [`AGENTS.md`](../../AGENTS.md).

If the test task stops at once with "property 'roomSchemas' specifies directory ... which doesn't
exist", the schema folder Room generates has been cleared by an incremental build. Regenerate it, then
run the tests again:

```bash
cd android && ANDROID_HOME=~/Library/Android/sdk ./gradlew :app:kspFullDebugKotlin --rerun-tasks
```

It takes seconds. `--no-build-cache --rerun-tasks` on the test task also works and takes minutes.

The newest backup in `whoop-data/lhoop-backups/` is replayed by `StrapSleepBackupCheckTest` and staged
for restore by `BackupRestoreNamesTest`. Both are skipped where there is no backup.
