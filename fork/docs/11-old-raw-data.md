# Old one-second rows: what to do with them

Written on 2026-10-09 for the owner to decide. Nothing here is built. The app deletes nothing today
and does not write to the core's database (AGENTS.md); two of the options below would change that,
and that is his call alone.

## What was measured

From the backup pulled on 2026-10-09, which holds 4.98 days of worn time (429,892 seconds of heart
rate). The database file is 174 MB and the exported backup, a zip of it, 44 MB.

| Table | Rows | Table + its index | Per row | Per day worn | Capped by the core |
|---|---|---|---|---|---|
| `gravitySample` | 429,962 | 34.3 MB | 84 B | 6.9 MB | no |
| `skinTempSample` | 429,962 | 23.1 MB | 57 B | 4.7 MB | no |
| `stepSample` | 429,962 | 21.2 MB | 52 B | 4.3 MB | no |
| `hrSample` | 429,892 | 20.2 MB | 49 B | 4.1 MB | no |
| `sleepStateSample` | 429,962 | 20.1 MB | 49 B | 4.1 MB | no |
| `rrInterval` | 271,105 | 18.7 MB | 72 B | 3.7 MB | no |
| `v18AuxSample` | 429,962 | 32.7 MB | 80 B | 6.6 MB | yes, at 604,800 rows: a week |
| `ppgWaveformSample` | 14,322 | 1.3 MB | 101 B | 0.3 MB | yes, at 604,800 rows: about 200 days |
| everything else | | 1.8 MB | | | |

- **The six uncapped tables grow by 27.8 MB for every day the strap is worn.** That is 0.85 GB a
  month and about 10 GB a year. The two capped tables level off at about 46 MB and 60 MB.
- **A third of each table is its index.** Every row carries the strap's id as text and each table's
  primary key is kept a second time as an index. Changing that would change the schema, which must
  stay as it is so that old backups restore.
- **The backup grew by 7.3 MB a day** between the exports of 6 October (27.5 MB) and 9 October
  (44.2 MB). At that rate an export is about 250 MB after a month, 700 MB after three and 2.7 GB after
  a year.
- **The phone had 31 GB free.** At 10 GB a year that is about three years.

So the phone's storage is not what runs out first. The export does: it is the safety step before
every install, each one stays in the phone's Downloads, and the morning routine pulls a whole one to
the Mac and unpacks it (twelve of them already take 655 MB there). Restoring one into an emulator, the
rehearsal for a new build, grows with it too.

## What needs the old rows

- **Nights already found do not.** A sleep, its heart figures and its stage split are kept in the
  app's own store (`SleepStore`) once they settle. Scores, Trends and Progress read those.
- **Opening a night does.** Its strip and its heart-rate curve are read from `sleepStateSample` and
  `hrSample` each time (`StrapSleepLoader.detail`).
- **A change of rules does.** When `SleepStore.RULES` is raised, every sleep in the last 46 days
  (`StrapSleepLoader.HISTORY_DAYS`) is found again from the raw rows: state for the sleep, heart rate
  and R-R for its figures, gravity as well for its stages.
- **Research does.** Making REM believable means running candidate stagers over real nights that have
  a reference beside them ([06-validation-plan.md](06-validation-plan.md)). Those nights are the
  project's asset, and they cannot be recorded again.
- **Nothing of the app's own reads steps or skin temperature.** The core does, for its own figures:
  the skin-temperature line in "Figures from the original engine" and its strain score.

## The options

| | What it does | Saves | Costs |
|---|---|---|---|
| **A. Leave it** | Nothing. | Nothing. | 10 GB a year on the phone, which it has room for. Exports reach hundreds of MB within months. |
| **B. Lighter routine, nothing deleted** | The tools read the `.lhoopbak` without unpacking it, and old exports are cleared from the phone and the Mac by hand. | About four fifths of the space the routine uses on the Mac: an unpacked backup is four times its zip. | Does nothing for the phone or for the size of one export. |
| **C. Trim by hand after an export** | A button on the Strap tab: "Delete one-second rows older than 90 days". It works only straight after an export in the same session, says how many rows and MB, and first keeps each trimmed night's minute-by-minute state and heart rate in the app's own store so its chart still draws. | The phone's database stops at about 2.5 GB and an export at about 700 MB. | The app writes to the core's database for the first time. On the phone it cannot be undone; the rows live on only in the exports kept on the Mac. A trimmed night can no longer be re-found or re-staged on the phone. |
| **D. Trim automatically** | The same as C on a timer, as the core does for its two capped tables. | The same. | The same, without the owner seeing it happen, and with no export guaranteed first. |
| **E. Drop only what the app does not read** | Trim `stepSample` and `skinTempSample` older than 90 days. | A third of the growth: about 9 MB a day. | Also a write to the core's database. The core's own skin-temperature and strain figures lose their history. Exports still grow by two thirds as fast. |
| **F. Thin instead of trim** | Replace rows older than 90 days with one row a minute. | 98% of the old rows. | The core's schema has nowhere to put a minute row, so the thinned data would have to live in a store of the app's own and the rows be deleted all the same: C with more code. |

## Recommendation

**Do B now and decide on C when an export passes 500 MB**, which at today's rate is in the second
week of December. Until then nothing is deleted anywhere, and the raw nights that the REM work needs
keep piling up where a new rule can still be run over them.

C is the one to pick then, in that form: by hand, only after an export, 90 days kept. Ninety days is
twice the 46 the app ever looks back, so a change of rules still reaches every night the screens can
show. The exports on the Mac become the archive, which they already are.

What it needs from the owner: a yes to one narrow exception to "never write to the core's database",
for deleting old rows from the six tables above and nothing else, and the habit of keeping the export
that the trim follows.

If the answer is no, A is safe for about three years of phone storage, and the export size is then
the thing to live with.
