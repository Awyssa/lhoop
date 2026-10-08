# Decisions

## Decided

| Date | Decision | Why |
|---|---|---|
| 2026-10-02 | Run the original app on Android, built from source. | The phone is a Pixel 9a. The Mac has no Xcode, and the iPhone route was never wanted. |
| 2026-10-02 | Install a build of upstream `main`, not the v11.8.0 release. | Two Android fixes for the WHOOP 5.0 landed after v11.8.0: the keep-alive now starts on a 5.0 (`a4706c0a`, #2391), and weak signal no longer switches off auto-reconnect (`0bae209b`). |
| 2026-10-02 | Use the `.staging` release build type. | It has the same application id and signing key as upstream's published APKs, so either installs over the other. Debug builds are slower and use more battery. |
| 2026-10-02 | Collect WHOOP history by hand from WHOOP's web API, saved under `whoop-data/`. | It carries WHOOP's internal recovery sub-scores, which the official CSV export does not. |
| 2026-10-03 | The owner fetches WHOOP data; the assistant never uses the owner's login or token. | Account credentials stay with the owner. Scripted requests are also blocked by Cloudflare's bot detection, and that is not to be worked around. |
| 2026-10-03 | Remove everything Apple: the iOS, watchOS and macOS apps, the Swift packages, and the Swift-only tools, CI and docs. | Android never shared code with them. One platform means a scoring change happens in one place. |
| 2026-10-03 | Keep pulling upstream's Android changes, through `fork/sync-upstream.sh`. | Upstream is very active and is still fixing WHOOP 5.0 behaviour on Android. |
| 2026-10-03 | Do the cleanup on a branch (`android-only`), commit it, and push nothing. | `main` stays untouched until the owner chooses to merge. The GitHub fork is public. |
| 2026-10-03 | Health data never goes into git. | The repository is treated as public. `whoop-data/` is listed in `.gitignore` (until 2026-10-06 it was ignored only through `.git/info/exclude`, which a new repository would not have). |
| 2026-10-03 | Build the owner's own app on the original app's foundation, and cut the rest. | The original app solved connecting to the strap and replacing WHOOP's app. The rest is heavy and not aimed at sleep and recovery. The foundation is 23% of the original app's Android code. |
| 2026-10-03 | Cut Oura, the importers, widgets and the AI coach, and with them the old UI, push, the update check, notifications, alarms, location and Polar. | Not wanted. They were wired into the old screens in about 230 places, so they went together with the UI in one cut. |
| 2026-10-03 | Cut around the Bluetooth code, not through it: `ble/`, `protocol/`, `data/` and `analytics/` stay byte-identical to upstream, and stand-ins satisfy what they expect. | The Bluetooth client is one 13,000-line file that cannot be tested without the strap, works today, and is changed upstream about twice a day. Untouched files keep working and keep merging. Proven by compiling it: no kept file needed an edit. |
| 2026-10-03 | Keep `analytics/` whole for now. | It produces the numbers until the app's own scoring exists. Thin it afterwards. |
| 2026-10-03 | No network. | The only network code was in the AI coach, push and the update check. With them gone the `INTERNET` permission is removed, so Android itself keeps the app offline. |
| 2026-10-03 | The smart alarm is cut, and the app leaves the strap's alarm slot alone. | Chosen as the default when the question went unanswered: it leans on sleep detection that is not validated yet. It can be rebuilt later. |
| 2026-10-03 | One branch, `main`. Commit to it directly. | The owner: this project is to get the app working, not to control or learn from every step. Branches protect nothing here; what matters is what is installed on the phone. |
| 2026-10-03 | Tag every build that goes on the phone, and check a baseline night before installing a build that changes strap behaviour. | `main` can then hold unfinished work without risk to recorded nights. |
| 2026-10-03 | Show the app's own WHOOP-style scores beside the core's, labelled experimental, with the core's as the headline for now. | The model matches WHOOP's recovery to 7.8 points on the WHOOP history, but only on WHOOP's own inputs. It has not been run on nights this app measured. |
| 2026-10-03 | The usual sleep need is a setting, 8 hours until changed. | WHOOP learned a personal value; that number is health data and stays out of the code. |
| 2026-10-03 | Push `main` to the owner's GitHub fork as work lands. | The owner: "commit and push as much as you want". Scan for health data before each push: the repository is public. |
| 2026-10-05 | Build each night from the strap's own asleep flag, not from the core's sleep sessions. | The flag matched all three sleeps so far. The core's detector was wrong on both nights, and a gate that keeps or drops whole sessions cannot fix a session that is half right. |
| 2026-10-06 | Rename the app LHOOP, everywhere: packages, files, identifiers, the application id, the backup format, the notes. | The owner's decision. The licence files, other people's addresses and unrelated words that contain the letters keep the old name ([`AGENTS.md`](../../AGENTS.md), "The name"). |
| 2026-10-06 | Stop following upstream by merge. | The rename changed the package of every file, so git can no longer merge upstream. The sync script was removed. Upstream's fixes are applied by hand from now on. This answers "cut the cord to upstream?". |
| 2026-10-06 | The assistant never stages, commits or pushes. The owner does all of it himself. | The owner: "never git add, git commit, and or git push ever", after the assistant had pushed nine commits unasked. This replaces the two rows of 2026-10-03 below that said to commit to `main` directly and to push as work lands. |
| 2026-10-06 | A new repository with a fresh history, on branch `master`. | The owner deleted the old GitHub repository and started this one after the rename. Commit ids and the `phone-…` tags from before that day no longer exist; builds that went on the phone are told apart by date, and their APKs are kept outside the repository. Treat the repository as public until told otherwise. |
| 2026-10-06 | The strap's state 3 ("up") counts as sleep, except its last 10.5 minutes where the strap then says awake. Shown as "restless". | The owner was asleep through two hours of it, and the Garmin put most of two shorter spells as sleep. Where it ends in awake, its last 10.5 minutes are the strap confirming the wearer got up. This replaced, the same day, a rule that counted only state 2 and lost those two hours. |
| 2026-10-06 | The app keeps its sleeps in a JSON file of its own, with a rules version. | The core's database must keep upstream's schema. A file is the least machinery, and every record can be worked out again from the strap's rows. |
| 2026-10-06 | The app's own recovery leads the screen, labelled experimental. The core's figures have a card of their own. | The core's scores are built on its own sleep detection, which was wrong on two nights of three. Both recoveries were still blank, so nothing on screen changed meaning. This replaces "the original app's as the headline for now" below. |
| 2026-10-06 | Move the phone to LHOOP through a backup: Export in the old app, Import in LHOOP, then connect. The old app stayed installed, stopped, until the owner removed it later that evening. | A renamed build is a different app to Android and starts empty. The core's own restore reads a backup made under the old name, so only a button was needed. Done that evening with nothing lost; the steps and checks are under "Moving to the renamed app" in [08-runbook.md](08-runbook.md). |
| 2026-10-06 | A day's night is the sleep that overlaps 21:00 to noon most. Other sleeps are listed and not scored. | It names the sleep that happened at night without assuming when the wearer wakes. Credit for other sleeps waits for a rule fitted on the WHOOP history. |
| 2026-10-06 | Other sleeps count. A nap's time asleep comes off the need of the night after it, and the nap is time asleep when days are compared for consistency. | It is how WHOOP treated the 22 naps in the history. Either half alone made the nights after a nap worse; together they cut the sleep-score error on those nights from 11.3 points to 8.0 and left ordinary nights as they were. This replaces "listed and not scored" in the row above. See [05-whoop-scoring-model.md](05-whoop-scoring-model.md). |
| 2026-10-06 | Three tabs in one dark look: Today, Trends, Strap, with each night opening over them. | The owner found the first screen, one long list of rows, hard to read, and chose these from three drafts. Dark always, by his choice: it is read in bed in the morning. |
| 2026-10-06 | Show deep and REM, marked as estimates, and keep them out of every score. | The owner wants them each morning. They are the core's stager over the strap's night: deep looks right, REM reads about double. Shown with that said beside them, until the stager is fixed. |
| 2026-10-06 | "Progress" is the last 7 days against the 28 before, by plain means. | The owner asked for a way to see sleep and recovery moving over time. It is a rule of thumb for the screen, not a fitted score, and it says nothing with too few nights. |
| 2026-10-06 | Every screen says whether the strap is being recorded. | That evening the app was frozen by Android for a while and nothing on the home screen showed it. |
| 2026-10-04 | The app counts only sleep the strap itself registered. | On the first night the core counted an hour before bed that the wearer was awake for. The strap's own state had that hour as awake or still, and the real sleep as 97% asleep. |
| 2026-10-03 | Run the cut build on the phone from tonight. | It installed over the original with the data intact and passed the connection, bond, sync and export checks. The original APK is kept for rollback, and both builds use the same database version. |
| 2026-10-03 | Pause `fork/sync-upstream.sh`. | It predates the cut. It must learn the removed paths, the kept files inside removed folders, and how to refresh the copied settings before it runs again. |

## Agreed direction, details still open

- **Validate with a Garmin worn alongside the WHOOP,** then fix gaps step by step. The Garmin is a
  second opinion, not the answer key. See [06-validation-plan.md](06-validation-plan.md).
- **The first real screen** shows last night's time asleep, HRV, resting heart rate and recovery as
  plain numbers. Suggested on 2026-10-03; the owner has not said what they want on it.

Superseded on 2026-10-03: "leave upstream's files structurally alone and add new work behind toggles".
That suited improving the original app. The core is still left alone, but the app above it is now
replaced, not extended.

## Open

| Question | Notes |
|---|---|
| A new icon? | The launcher icon and the demo videos are pictures from the original app. Renaming files does not change what is drawn in them. |
| Keep the original project's own documents? | `docs/`, the release notes, and the support, security, contributing and conduct pages were written by the original project about itself. They were renamed with the code and now read oddly. Deleting them was on the roadmap already. |
| Which Garmin model? | Decides whether nightly HRV, skin temperature and file export are available. |
| How does Garmin data reach the Mac? | A Garmin Connect script the owner logs into (richest), Health Connect (no HRV), or typing numbers in by hand. So far: screenshots of the sleep page taken over USB. |
| How much of a short spell of state 3 is awake? | About eight minutes, by the Garmin, on two spells. The app counts the whole spell as sleep. See [04-sleep-recovery-engine.md](04-sleep-recovery-engine.md). |
| Report the 100,000-row cap to upstream? | Bug 3 in [04-sleep-recovery-engine.md](04-sleep-recovery-engine.md). It only bites a strap worn round the clock. The owner's call. |
| Remove non-code clutter? | Demo videos (24 MB), upstream release notes, design notes, maintainer scripts, upstream docs for removed features. Not answered; on the roadmap as housekeeping. |
| Switch to a debug build for development? | It would let the database be read over USB directly. The app would be slower. The alternative is the Export backup button. |
| Buy a Polar H10 chest strap? | A few nights would give a true reference for HRV. |
| Is there WHOOP history before August 2025? | Only if a WHOOP was worn then. |
| 2 and 3 June 2026 | Two nights that fall between two downloads. Optional. |
