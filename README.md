# LHOOP: a personal sleep and recovery app for the WHOOP 5.0

A personal Android app that reads a WHOOP 5.0 strap over Bluetooth and turns its data into **sleep and
recovery**: time asleep, overnight HRV, resting heart rate and a recovery score. No WHOOP membership,
no account, no cloud. The app has no network code and no internet permission.

> **Where the code came from.** LHOOP began as a copy of
> [NOOP](https://github.com/ryanbr/noop) and keeps that app's Bluetooth, protocol, storage and analytics
> code. Everything else in it was removed, the app on top was built new, and the whole was renamed. Use
> the original project for the full multi-platform app (macOS, iOS, Android), its releases and its
> community. Its licence still governs the code taken from it: see "License and attribution".
>
> **Not affiliated with WHOOP.** This is an independent interoperability project. "WHOOP" only
> identifies the hardware it talks to. It is **not a medical device**; every metric is an approximation.
> See [`DISCLAIMER.md`](DISCLAIMER.md) and [`TERMS.md`](TERMS.md).

## Status

Early. The app connects to the strap and stores what it records. It finds each night's sleep in the
strap's own sleep state, works out HRV and resting heart rate over that sleep, and scores it with a
model fitted to the owner's WHOOP history. It has two screens: last night's sleep and recovery, and the
strap's status. The code builds and passes its unit tests. Before the rename it ran on the owner's
phone, where connecting, bonding, syncing and exporting were confirmed on the strap and three nights
were recorded; the renamed build has not been installed yet. One night has been compared with a second
device and agrees closely. That is far too few to call it validated, and the scores are labelled
experimental. See [`fork/docs/07-roadmap.md`](fork/docs/07-roadmap.md).

## How it differs from the original app

- **Android only.** The iOS, watchOS and macOS apps and the Swift packages are gone.
- **Core only.** The original UI, widgets, AI coach, importers, Oura support, push, update check,
  notifications and alarms are gone. The Bluetooth, protocol, storage and analytics code is kept.
- **Renamed.** Every package, file and identifier carries the new name, so the original project's
  changes can no longer be merged in; they have to be applied by hand.
- **Stand-ins** under `android/app/src/main/java/fork/standins/` declare what the core still expects
  from the removed code.
- **The app itself** is under `android/app/src/main/java/fork/app/`.

The layout is described in [`fork/docs/10-app-structure.md`](fork/docs/10-app-structure.md).

## Build and install

Requirements: JDK 17+ (JDK 21 works), the Android SDK (platform 35), and a phone with USB debugging.

```bash
cd android
ANDROID_HOME=~/Library/Android/sdk ./gradlew assembleFullRelease -PstagingRelease
adb install -r app/build/outputs/apk/full/release/app-full-release.apk
```

It installs as `com.lhoop.whoop.staging`. Builds are signed with `android/fork-debug.keystore` when
that file is present. It is not in this repository, so a fresh clone signs with its own machine's debug
key, and Android will not install that over an app signed with another key. The key the owner uses is
the original project's public one, so a signature proves nothing about who built an APK: install only
APKs you built yourself. A build made before the rename has another package name, and Android treats
the two as different apps: see [`fork/docs/08-runbook.md`](fork/docs/08-runbook.md) before installing
over a phone that has one.

Unit tests (JVM, no device needed):

```bash
cd android && ANDROID_HOME=~/Library/Android/sdk ./gradlew testFullDebugUnitTest
```

## Personal data

Raw strap data, backups and WHOOP exports are health data. Keep them out of git. The local
`whoop-data/` folder is listed in `.gitignore`.

## Docs

- [`fork/docs/`](fork/docs/README.md): project notes (goals, decisions, findings, layout, roadmap,
  runbook).
- [`AGENTS.md`](AGENTS.md): how to work on this repository.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) and its siblings: the original project's notes on the WHOOP 4.0
  and 5.0/MG Bluetooth protocol.
- [`docs/ANALYTICS.md`](docs/ANALYTICS.md): how the core computes recovery, strain, HRV and sleep.

The documents under `docs/` and the release notes were written by the original project about its own
app. They were renamed along with the code, so they say LHOOP where their authors wrote the original
name, and many describe features and files this repository removed.

## License and attribution

The code taken from the original app is source-available under the
[PolyForm Noncommercial License 1.0.0](LICENSE), Copyright 2026 NoopApp. It is free for personal and
other non-commercial use; commercial use is not granted. This repository keeps that licence and its
notice exactly as they were, original name included, as the licence requires. Credits for the community
protocol work the core builds on are in [`ATTRIBUTION.md`](ATTRIBUTION.md) and [`NOTICE`](NOTICE).
# lhoop
# lhoop
