# Background and risks

What the original app is, how far to trust it, and what to be careful about. Findings from the review
on 2026-10-02.

This app began as a copy of NOOP ([github.com/ryanbr/noop](https://github.com/ryanbr/noop)) and was
renamed LHOOP on 2026-10-06. These notes call NOOP "the original app" or "upstream". Its licence and
copyright notice still apply to the code taken from it: see `LICENSE` and `NOTICE`.

## What the original app is

An independent app, written without WHOOP's code, that talks directly over Bluetooth to a WHOOP strap
you own. It stores everything on the device and computes recovery, strain, sleep and HRV itself, from
published sports-science methods. It is not a fork of WHOOP's app and does not use WHOOP's servers or a
WHOOP account.

## How the project has gone

- First released in June 2026 by an anonymous developer. GitHub took the repository down that month and
  reinstated it on appeal.
- In July 2026 the original repository disappeared. A contributor, `ryanbr`, took the project over, and
  that repository is now the canonical one.
- It moves very fast: hundreds of commits a month, a stable release every week or two, and a testing
  build most days. In practice it has one maintainer.
- WHOOP has asked the developers of other unofficial apps to take them down, and says unofficial apps
  may breach its terms.

## Privacy review: the claims hold

Checked against the Android code.

- No analytics, advertising or crash-reporting libraries. Nothing downloads code at runtime.
- No other app on the phone can read the health data.
- Network use is limited to:
  - a once-a-day check with GitHub for a newer release. It is on by default and sends nothing about
    you; switch it off in Settings → About;
  - the AI coach, only after you add your own API key;
  - a one-way push to a server you run yourself, off by default.
- Sensitive permissions are requested only when a feature is used: the microphone for the coach's voice
  button, phone state for call alerts, location for GPS workouts.
- The strap commands that could brick or wipe the strap (firmware load, ship mode, force-trim) are not
  in the app.

Since the cut to the core, this app has none of that network use: the update check, the AI coach and
push were removed, and so was the `INTERNET` permission. The sensitive permissions went with their
features. The original build 550 on the phone still has them until the cut build replaces it.

Caveats:

- The database is not encrypted beyond Android's app sandbox, and backups are plain zip files.
- Some of the project's privacy text is out of date, for example about the update check being off by
  default.

## Risks to keep in mind

- **Terms of service.** The original app's own terms say using it may breach WHOOP's. No reports of users being
  penalised were found; WHOOP's documented action has been against developers.
- **The signing key is public.** Anyone can build an APK that installs over this app. Install only
  builds made here or from upstream's releases.
- **Test Centre** (original build only). Some experiments write lasting settings to the strap, and one
  sends a power-cycle command. Use only the Debug logging switch. The cut build has no Test Centre.
- **The cut build is new on hardware.** Connecting, bonding, syncing and exporting were confirmed on the
  strap on 2026-10-03, and a complete night with the link held for 16 hours on 2026-10-04. One night is
  not a track record. The original
  APK is kept to roll back to.
- **Copied settings can go stale.** The stand-ins that copy upstream's settings code do not follow
  upstream by themselves. See [10-app-structure.md](10-app-structure.md).
- **Uninstalling wipes everything.**
- **Upstream could vanish again.** This fork has the full Android source, and upstream's licence allows
  mirroring.
- **The scores are estimates.** They are not WHOOP's algorithms and not medical data. See
  [04-sleep-recovery-engine.md](04-sleep-recovery-engine.md) for how accurate they are.

## Licence

PolyForm Noncommercial 1.0.0. Personal and other non-commercial use is free; commercial use is not
granted. Keep the licence and copyright notice, and point people to upstream as the canonical project.
