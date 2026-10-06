# WHOOP 5.0 on Android: status

Upstream calls WHOOP 5.0/MG support experimental: live heart rate works, pairing is the hard part, and
the deeper data only flows once the strap is fully paired. This page records what is true for this
strap and this phone.

## Pairing (Confirmed, 2026-10-02)

The strap paired fully with the Pixel 9a (Android 17).

- The strap was put into pairing mode: tap the band firmly and repeatedly until the lights flash blue.
- The original app asked Android to pair, Android showed its pairing prompt, and the bond completed in
  about seven seconds. Android reports an encrypted link (LE legacy pairing, user consent, 16-byte key).
- Over the following hour the link never dropped and the bond held. The app read the signal strength
  once a minute, which is the keep-alive routine from fix #2391, so that fix is active in the installed
  build.
- It wrote rows to its database, ran its analysis and started its background service.

Many 5.0 owners never get past this step on Android (upstream issue #1635: some straps answer every
pairing request with "Pairing Not Supported"). The reports of success share the same recipe: pairing
mode, and no other device holding the strap.

## What a paired 5.0 delivers (Reported)

Without a bond, the strap offers only the standard Bluetooth heart-rate profile: heart rate, R-R
intervals and battery. With a bond, the original app sends CLIENT_HELLO, sets the strap's clock and pulls the
stored history.

| Signal | Where it comes from | Stored on Android in |
|---|---|---|
| Heart rate, once a second | History record v18; also the standard profile | `hrSample` |
| R-R intervals, up to 4 a second | History record v18 (source 5); standard profile (source 7); live packet 40 (source 6) | `rrInterval` |
| Gravity / motion | History record v18 only. On this strap (firmware 50.40.1.0) every one-second record carries it; an earlier audit expected about a fifth | `gravitySample` |
| Skin temperature | History record v18 only | `skinTempSample` |
| The strap's own state: awake, still, asleep, up | History record v18 only | `sleepStateSample` |
| Optical (PPG) waveform | History record v26 | `ppgWaveformSample`, and a derived `ppgHrSample` |
| Blood oxygen | An unvalidated candidate byte in v18 | `v18AuxSample` only; never shown as a score |
| Respiration | No raw channel on a 5.0 | Estimated from R-R at scoring time |

**The strap does not provide deep, REM or light sleep.** WHOOP computes stages on its servers. Any app
has to compute its own.

History offload runs on connect and then every 15 minutes while connected. The strap keeps about 14
days. Each chunk is saved before it is acknowledged, and the strap frees what has been acknowledged, so
history taken by the original app never reaches WHOOP's app and the other way round.

## Checked on this strap (Confirmed, 2026-10-03)

- **History offloads.** A backup taken from the original build held gravity, skin-temperature and
  sleep-state rows, which only come from history. On the cut build a sync logs the strap's
  "History burst success" and "Historical Dump Complete", the app acknowledges the chunk, and the
  session ends with `HISTORY_COMPLETE`.
- **The strap kept its own log while unused.** Event and battery rows reach back to 30 June 2026, so
  its clock was valid before pairing.
- **It records sensor data only while worn.** The strap had not been worn since it was paired, and the
  only heart-rate, motion and temperature rows are from about a minute on the evening of 2 October. A
  sync of the unworn strap reports events and no data records.
- **Firmware 50.40.1.0.**
- **A full night records, with nothing missing.** The first night worn (3 to 4 October) gave 15.2 hours
  of one-second records with no gap: heart rate, gravity, skin temperature, sleep state and steps for
  every second, about 1.1 R-R intervals a second (fewer than the beats; the strap does not store every
  one), and an optical burst every 30 seconds or so. All of it arrived through history sync.
- **R-R intervals are stored in milliseconds on this firmware.** Over three nights, in five-minute
  windows the strap called asleep throughout, the stored intervals add up to at most 1.003 of the
  window's length, and nearly half the windows sit at 1.00. Ticks of 1/1024 second would pile up at
  1.024. Where nearly every beat is stored, heart rate worked out from the intervals is 0.9998 of the
  strap's own (median of 151 windows). The protocol docs describe ticks for firmware 50.42.1.0; that
  firmware has not been seen here.
- **The link holds in the background.** The app ran for 16 hours, overnight with the phone locked,
  without a restart, a crash or a hole in the data.
- **The cut build behaves like the original on the link.** Installed over the original on the Pixel 9a,
  it connected by itself, showed an encrypted bond, synced, exported, and re-bonded after a manual
  Disconnect and Connect in about ten seconds.

## Open: to check on this strap

- **Recovery after Bluetooth is toggled or the strap goes out of range.**
- **How the strap's state behaves on more nights.** State 3 ("up") begins with movement during a sleep
  and ends when the strap confirms the wearer asleep again or awake. The owner slept through one long
  run of it, so the app counts it as sleep, less the strap's wake confirmation. Three nights is not
  many. See [04-sleep-recovery-engine.md](04-sleep-recovery-engine.md).

## Known weak points (Reported)

- **R-R units on other firmware.** The code reads 5.0 R-R values as milliseconds (since #2195, measured
  on firmware 50.41.1.0), which is confirmed above for 50.40.1.0. The protocol docs describe
  1/1024-second ticks for 50.42.1.0. The difference would shift HRV by about 2.4%, so check again
  after a firmware update.
- **One R-R source per read window.** Scoring picks a single R-R source for each window of about 54
  hours. Any history beat in the window hides all standard-profile beats, so a partial offload could
  shrink a night's HRV coverage. WHOOP 4.0 got per-hour selection; the 5.0 did not.
- **Gaps in heart rate are filled from the optical waveform.** That estimator was validated on a single
  match, and it works on fixed one-second records, the pattern upstream itself warns can fake a signal.
- **A date-range check that only Android applies.** Android uses an unconfirmed decode of the strap's
  data range to reject history records more than 7 days outside it. If that decode is wrong, real
  nights could be dropped.
- **"Continuous HRV capture"** only turns on live packet 40, whose R-R is stored as source 6 and never
  scored.

## Operating rules

- One device at a time. Do not let the WHOOP app, another phone or a Mac connect to the strap.
- In the original build, stay out of Test Centre's experiments: some write lasting settings to the
  strap. The **Debug logging** switch there is safe. Do not tap **Recalibrate Charge baseline**. The
  cut build has no Test Centre; its only switch is Debug logging on the status screen.
- Uninstalling the original app wipes its data. Update by installing over it.

## What others have achieved

- On iPhone and Mac, paired 5.0 history offload is routine for upstream's users.
- On Android there are confirmed successes, all using pairing mode.
- An Android-only fork of the original app, `tanarchytan/noop`, claims hardware-verified 5.0 sleep
  staging, recovery and HRV. The claim is self-reported; it is worth reading for ideas.
- Nobody has found the history encrypted or tied to a WHOOP membership.
