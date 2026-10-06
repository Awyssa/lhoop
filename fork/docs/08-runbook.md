# Runbook

How to do the recurring things. Commands run from the repository root unless stated.

**The app was renamed LHOOP on 2026-10-06, and that changed what a build is to the phone.** Read this
first:

- **What this code builds** installs as `com.lhoop.whoop.staging`, labelled LHOOP. No build of it has
  been installed anywhere.
- **What the phone runs** is the last build made before the rename (the second build of 6 October),
  under the package name the app had then. It holds every recorded night.
- **To Android these are two different apps.** Installing a build of this code does not replace the
  app on the phone: it appears beside it, empty, with no saved strap. Nothing moves the data across by
  itself. See "Moving to the renamed app" below before installing one.
- **The original build** (the unmodified original app, build 550 from upstream `c800fb61`) is kept as
  an APK. It carries the old package name too, so it rolls back the old app, not the renamed one.

## Build

Needs JDK 17 or newer (21 works) and the Android SDK.

```bash
cd android
ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleFullRelease -PstagingRelease
```

The APK is `android/app/build/outputs/apk/full/release/app-full-release.apk`.

## Moving to the renamed app

Not done, and not to be done casually: the old app is the one recording.

1. Export a backup from the old app and pull it to the Mac (`APP_PACKAGE=<the old package name>
   fork/tools/pull_night.sh`).
2. The renamed app needs a way to restore it. The core has one (`DataBackup.importFrom`), and it accepts
   a backup written under the old name: `BackupRestoreNamesTest` stages every backup pulled so far. But
   **there is no Import button yet**; the screen that had one was cut. That button has to be built and
   tried in the emulator first.
3. Two apps must not both hold the strap. Disconnect the old one (its Strap tab) before the new one
   connects, and expect to tap Connect in the new one: the saved strap address lives in the app's
   settings, which a backup does not carry under the new names.
4. Check a night in the new app against `fork/tools/night_report.py` before removing the old app.
   Removing an app deletes its data.

What a restore will not carry: the old app's settings (their names changed), and the days the core had
already scored (they are stored under an id that ended in the old name; the core scores recent days
again from the raw rows).

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

Results for the build of 3 October 2026: checks 1, 2, 3, 4, 7 and 9 passed on 2026-10-03, and
checks 5 and 8 on the first night (3 to 4 October): 16 hours in the background and a complete night.
Checks 6 and 10 have not been run. The strap used 4% of its battery over that night.

The builds of 4 and 6 October (two that day) changed nothing in how the app talks to the strap. Each
was installed over the last, started with `adb shell monkey -p <package> -c
android.intent.category.LAUNCHER 1` while the phone was locked, and seen in the log to reconnect and
finish a sync (checks 1, 2 and 4). All of those were builds from before the rename.

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
| 9 | Export works | Export backup proposes `lhoop-backup-<date>.lhoopbak` and says "Backup exported." The file is a zip holding `lhoop-backup.sqlite`. (The app on the phone, built before the rename, uses its old name in all three.) |
| 10 | Battery | Strap and phone drain over a night is about what it was on the original build. |

After a phone restart or a force-stop, nothing records until the app is opened once. That is upstream's
behaviour and is not a failure.

Installing the cut build over the original silently drops anything the original had set up outside the
core: placed widgets, wrist notifications, a phone alarm or wind-down reminder, daily auto-backup and
Health Connect access.

## Phone settings

- Settings → Apps → the app → App battery usage → **Unrestricted**. Set on 2026-10-03.
- Open the app once after every phone restart or app update.

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
While the phone still runs the build from before the rename, tell it which app to drive:
`APP_PACKAGE=<the old package name> fork/tools/pull_night.sh` (`adb shell pm list packages whoop`
prints it). Both tools read a backup whatever the app that wrote it was called.
It ran as a script for the first time on 6 October and worked; it has not run since the rename. The second prints how complete the
recording is, the sleeps in the strap's own state by the app's rules, with heart rate and HRV worked
out independently from the raw data, the core's sessions beside them, and what the core stored. Add
`--hours 90` to see several nights.

Then compare with the app: its Last night tab should show the same time in bed, time asleep and awake
time as the report's newest night, HRV within a millisecond or so, and resting heart rate within a beat
(the report uses simpler arithmetic for those two on purpose). To replay the app's own code over the
backup on the Mac:

```bash
cd android && ANDROID_HOME=~/Library/Android/sdk ./gradlew testFullDebugUnitTest --tests "fork.app.StrapSleepBackupCheckTest"
```

Its output is in `android/app/build/test-results/testFullDebugUnitTest/TEST-fork.app.StrapSleepBackupCheckTest.xml`.

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
