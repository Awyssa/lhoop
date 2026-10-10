# Backing up to the owner's server: the plan

Written on 2026-10-09, and brought up to date on 2026-10-10 with the owner's answers about his server.
The owner decided that the app may send its data to a server of his own (a Hetzner VPS), so that
losing the phone or the app no longer loses the nights. This page is the plan for the server, its
Docker setup and what it stores. The app's side is outlined at the end and gets its own detail when
it is built. What is built so far is under "Order of work".

It lifts one hard limit: the app gets the `INTERNET` permission back, for this upload and nothing
else. See "What changes in the app".

## The shape in one paragraph

The server keeps **a copy of the phone's database in the phone's own format**. Once a day the app
sends only the hours that are new or changed, the server merges them in, and both sides check they
agree. Getting data back means asking the server for a backup file, which is the same `.lhoopbak` the
app's Import backup already restores. Nothing on the public internet can read data out: the public
API only takes data in, and reading goes over SSH.

## Why a copy in the same format, and not new tables in Postgres

- **Restore is already tested.** Import backup has been used on the phone and in the emulator. A new
  schema would need a new, untested way back, and a backup is only as good as its restore.
- **Nothing shared is touched.** The copy is one file in one folder owned by one container. No schema
  is added to a database other projects use, and removing the whole thing is deleting a folder.
- **The copy is exact.** SQLite to SQLite keeps every value as stored, decimals and binary included.
- **The Mac tools already read it.** `night_report.py` and `stage_whatif.py` work on an unpacked
  backup today.

If dashboards over the data are wanted later, a loader can fill Postgres from the copy. That is a
separate, read-only job and no part of the backup.

## What was measured

A trial on the Mac, on the backup pulled on 2026-10-09 (194 MB, 36 tables). It split the database
into one upload per day, merged them into an empty copy built from the schema alone, and compared.

| | |
|---|---|
| Copy against the source | Identical row for row in all 36 tables; same file size; integrity check passed |
| One full day of rows | About 567,000 rows, 20.5 MB as a file, **5.6 MB compressed** |
| Merging all of it (3.1 million rows) | About a second |
| Checksums for every hour of every table | 1.6 seconds for the whole database |
| One flag changed in place on one row | Showed up as exactly one hour that no longer matched |
| The copy packed as a backup file | 50 MB, 3 seconds |

These are a Mac's timings. A small VPS and the phone will be slower; nothing here is close to a limit.

## What the server stores

Everything lives in one folder, `data/`, mounted into the container.

```
data/
  replica.sqlite    the copy: the phone's 36 tables, unchanged
  ledger.sqlite     the server's own records (below)
  log/              every accepted upload, as received, compressed
  incoming/         uploads still being received
```

### `replica.sqlite`

Created from the table and index definitions the phone sends on its first run, with the same schema
version (41) and Room's identity row, so the app accepts it as its own. The server never invents a
column. The tables fall into two kinds by one rule:

- **Sample tables: the primary key includes `ts`.** Fourteen today: the one-second tables
  (`hrSample`, `rrInterval`, `sleepStateSample`, `gravitySample`, `skinTempSample`, `stepSample`,
  `v18AuxSample`), `event`, `battery`, the two optical tables and three that are empty. They are
  merged hour by hour, and **rows are only ever added or overwritten, never deleted**.
- **Everything else.** Twenty-two small tables (daily figures, sessions, the paired strap, Room's own
  rows; under 2 MB together). Sent whole each time and replaced whole.

Never deleting matters because the phone itself drops rows: the core keeps only a week of
`v18AuxSample`. The server keeps all of it, which makes it an archive as well as a backup.

### `ledger.sqlite`: the new schema

The only tables that are new. They hold no health data, only what arrived and when.

```sql
CREATE TABLE meta (              -- one row per fact about the copy
  key   TEXT PRIMARY KEY,        -- 'format', 'created_at', 'user_version', 'identity_hash', 'settings_json'
  value TEXT NOT NULL
);

CREATE TABLE upload (            -- one row per upload accepted or refused
  id          INTEGER PRIMARY KEY,
  received_at INTEGER NOT NULL,  -- server clock, seconds
  kind        TEXT NOT NULL CHECK (kind IN ('schema', 'delta')),
  sha256      TEXT NOT NULL UNIQUE,
  bytes       INTEGER NOT NULL,  -- as received
  row_count   INTEGER NOT NULL,
  app_build   TEXT,
  status      TEXT NOT NULL CHECK (status IN ('merged', 'refused')),
  error       TEXT,
  log_file    TEXT               -- name under log/, null when refused
);

CREATE TABLE bucket (            -- one row per table, strap and hour the server holds
  table_name  TEXT NOT NULL,
  device_id   TEXT NOT NULL,
  hour        INTEGER NOT NULL,  -- ts / 3600
  phone_print TEXT NOT NULL,     -- the phone's checksum of this hour, as last merged
  row_count   INTEGER NOT NULL,  -- rows the server holds for this hour
  upload_id   INTEGER NOT NULL REFERENCES upload(id),
  PRIMARY KEY (table_name, device_id, hour)
) WITHOUT ROWID;

CREATE TABLE export (            -- every time data was read out
  id      INTEGER PRIMARY KEY,
  at      INTEGER NOT NULL,
  days    INTEGER,               -- null for everything
  bytes   INTEGER NOT NULL
);
```

`bucket` grows by under 300 rows a day. The ledger stays small for years.

### `log/`

Each accepted upload is kept as it arrived. The copy can be rebuilt from the log alone, so a bad merge
or a damaged copy is recoverable, and the log is the part worth copying off the server: it is
append-only, so copying it is cheap.

## How the two sides agree: hour checksums

For each sample table, rows are grouped by strap and by hour (`ts / 3600`). An hour's checksum is a
list of whole numbers: the row count, then for each column in table order the sum of an `INTEGER`
column, or the summed length of a `TEXT` or `BLOB` column, plus the count of non-null values where a
column may be null. `REAL` columns are left out.

- **It is exact and the same everywhere.** Whole-number sums do not depend on row order or on the
  machine. Sums of decimals would.
- **It catches what actually changes.** A missing or extra row changes the count and the sum of `ts`.
  The only rows the core changes after writing them are in `rrInterval` (three whole-number flags, set
  up to days later when the same beat arrives from a better source), and those change a sum.
- **Leaving decimals out is safe today.** Decimal columns are only in `gravitySample`, `battery` and
  `ppgHrSample`, and the core never rewrites a row in those.
- It is a checksum against drift and mistakes, not against someone forging data.

The rule is written once, as a file of made-up rows with their expected checksums, and the server's
tests and the app's tests both read that file. That keeps the Go and the Kotlin in step.

## The API

Four routes, served over HTTPS behind the reverse proxy. All but the first need the token.

| Route | What it does |
|---|---|
| `GET /healthz` | Answers `ok`. No token, no information. |
| `PUT /v1/schema` | First run only. Takes the phone's `CREATE TABLE` and `CREATE INDEX` statements, schema version and identity row, and creates the empty copy. Any other kind of statement is refused, and so is the whole call once a copy exists. |
| `POST /v1/plan` | Takes the phone's checksums for a span of hours. Answers with the hours the server wants: those it lacks, or whose checksum differs from the one it last merged. Also says whether the schema is known and matches. |
| `POST /v1/delta` | Takes one compressed SQLite file holding the wanted hours in full, plus the small tables whole. The server checks it, merges it in one transaction, and records it. |

There is deliberately **no route that returns data**. See "Security".

### A daily run

1. The app works out the checksums of its last 15 days (the strap holds about 14, so nothing older can
   still change) and posts them to `/v1/plan`.
2. For the hours the server wants, the app writes delta files, at most a day of hours each, and posts
   them. A normal day is one file of about 6 MB.
3. The app posts the same checksums to `/v1/plan` again. An empty answer means everything it sent is
   merged. The app records the time and shows it on the Strap tab.

Once a week the same run covers every hour the phone holds, not just the last 15 days.

The first run is the same code: the server wants everything, and the app sends it a day at a time. It
can stop and resume at any point, because the server's answer says what is still missing.

### What the server checks before merging

- The token, compared in constant time. The server stores only its SHA-256.
- Size: at most 64 MB received and 512 MB unpacked, cut off while streaming.
- The file's SHA-256 against the header that came with it. The same file twice is answered from the
  ledger and merged once.
- The delta is opened as data only: integrity check, no views or triggers, only tables the copy has,
  with exactly the copy's columns. Nothing in it is ever run as SQL.
- The schema version and identity row match the copy's. If the phone's schema ever changed, the server
  refuses and says so. The project's rule is that it does not change.
- **The checksum of every hour in the file, worked out again by the server, equals the one the phone
  sent.** A damaged file or a difference between the two implementations stops here, on every upload.
- Free disk: at least twice the unpacked size.

Then, in one transaction: `INSERT OR REPLACE` the hours into the sample tables, replace the small
tables, and afterwards write the ledger rows and move the file into `log/`. If the server dies between
the two, the phone's next plan asks for the same hours and merging them again changes nothing.

## Getting data back

By a command inside the container, run over SSH. It is not on the internet.

```
lhoop-backup export --days 60 > lhoop.lhoopbak    # a backup file: database, settings, manifest
lhoop-backup status                               # tables, rows, first and last hour, last upload, disk
lhoop-backup verify                               # integrity check, and the copy against the ledger and the log
lhoop-backup token new                            # prints a new token once, and the line for .env
```

- `export` writes the same three entries the app's own export writes, so **Import backup restores
  it**. `--days` keeps only that many days of the sample tables and applies the core's own caps, so
  the file is what a phone would hold.
- **A restore to the phone needs `--days`.** The app's import refuses a database over 2 GiB unless
  asked to go on, and the app's Import button does not offer that today. Sixty days stays under it.
  The phone's own database passes 2 GiB around mid-December at today's growth. This is a fault in
  waiting whatever happens with the server, and fixing it is step 0 below.
- A Mac script, `fork/tools/server_pull.sh`, will run `export --days 3` over SSH and unpack it where
  `pull_night.sh` puts its pull, so the morning report can run without the phone on a cable. The
  Garmin capture still needs the cable.
- Restoring inside the app, with no Mac, is not in this plan. It would need a route that returns data.

## Security

The data is every second of the owner's heart rate, on a machine that faces the internet.

- **The public API is write-only.** A stolen token can add rows or overwrite them. It cannot read
  anything. Overwritten rows are recoverable from the log.
- **Reading needs SSH to the server**, which the owner already guards with his key.
- **One long random token** (32 bytes), made on the server, pasted into the app by the owner. Kept in
  the app's private storage, in a file of its own so that it is never part of an exported backup, and
  never logged. Rotating it is one command and a restart.
- **HTTPS only.** The app refuses a plain `http://` address, and Android is told to refuse cleartext.
- **The container** runs as its own user with a read-only file system, no capabilities, no new
  privileges, memory and process limits, and one writable folder. It publishes no port, so only the
  reverse proxy reaches it.
- **Logs carry counts, sizes and table names.** Never a row, never the token.
- **What this does not protect against:** anyone with root on the VPS, or the hosting company, can
  read the copy. Encrypting it on the phone is not possible in this design, because the server has to
  read rows to merge them. Only the owner logs in to this server, with a key kept in his password
  manager.
- **The server is not this project's alone.** Another service of the owner's already answers from the
  internet on it, through the same Caddy. A hole in that one is the other way onto the machine, which
  is why the copy sits in a folder only this container's user can read, why the container holds
  nothing but this, and why the two projects share the proxy's network and no other.
- **The repository is public.** The server's code is in it. The host name, the token and `data/` are
  not: they are in `.env` and in a folder that `.gitignore` excludes.

## The Docker setup

The server is a small Go service in this repository, under `fork/server/`. **Go because the owner
asked for it**: it is the language he maintains. It was first written in Python on 2026-10-10 and
rewritten in Go the same day, before anything was deployed; the protocol, the files on disk and the
tests carried over unchanged.

It builds to one static binary, and that binary is the only thing in the image: no shell, no
interpreter, no operating system, 14 MB in all. **It has one dependency**, the SQLite library
`zombiezen.com/go/sqlite`, which is SQLite itself translated to Go; Go has no SQLite of its own. That
library brings eleven modules with it, and all twelve are pinned by hash. Caddy still stands in front
and does the HTTPS and fetches the certificate. **The owner's server already runs a Caddy container**
for his other service, holding ports 80 and 443, so that Caddy fronts the backup server too, under a
second name. A second Caddy could not start beside it.

```
fork/server/
  main.go
  backup/               server.go (routes), replica.go (create, merge, export), checksum.go, ledger.go,
                        store.go, cli.go, client.go, and their tests
  testdata/             the shared checksum file
  go.mod, go.sum        the one library and its hashes
  Dockerfile            builds the binary, then an image holding nothing else
  .dockerignore         keeps data/ out of the build
  compose.yaml          the service alone, on a network it shares only with the reverse proxy
  compose.caddy.yaml    adds a Caddy, for a server that has no reverse proxy
  Caddyfile             the site block: used by compose.caddy.yaml, or copied into the Caddy already there
  .env.example
  README.md             the commands below, in full
```

`compose.yaml`, in outline:

```yaml
services:
  api:
    build: .
    restart: unless-stopped
    user: "10001:10001"
    read_only: true
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    mem_limit: 512m
    pids_limit: 128
    environment:
      LHOOP_TOKEN_SHA256: ${LHOOP_TOKEN_SHA256:?set in .env}
    volumes:
      - ./data:/data
    networks:
      proxy:
        aliases: [lhoop-backup]
    healthcheck:
      test: ["CMD", "/lhoop-backup", "healthcheck"]
    logging:
      options: { max-size: "10m", max-file: "3" }

networks:
  proxy:
    external: true      # made once with `docker network create proxy`
```

The service publishes no port. The only way in is the reverse proxy, over a Docker network named
`proxy` that the two share and nothing else is on. The existing Caddy joins that network and gets one
more site block, which sends the new name to `lhoop-backup:8787`. The backup container is not on the
other service's network, so it cannot see that service's database, and that service cannot see it.
One process: there is one writer, and uploads wait for each other.

**The existing Caddy** has a Caddyfile with one site, whose name comes from a variable, and a compose
project of its own on the server (seen on 2026-10-10). It can take the new site without a restart:
joined to `proxy` live, then a reload, which leaves the site it already serves untouched. Both
changes then have to be written into that project's own files, or its next deploy undoes them. The
steps are in `fork/server/README.md`.

### Putting it on the Hetzner server

The owner runs these; the assistant does not use his logins. Each is one or two commands, written out
in `fork/server/README.md` when it is built.

1. **A name.** The owner will register a domain at Cloudflare. A DNS record for a subdomain points at
   the server, set to **DNS only**, not proxied. Proxied, Cloudflare would decrypt every upload on its
   way through; DNS only, the phone talks to the server directly and Cloudflare sees nothing.
2. **The code.** Clone the public repository on the server and go to `fork/server/`.
3. **The network.** `docker network create proxy`, once.
4. **A token.** `docker compose run --rm api token new`. Copy the printed line into `.env`, and keep
   the token for the phone.
5. **Start.** `docker compose up -d --build`.
6. **The proxy.** The Caddy already there is joined to the `proxy` network while it runs, its
   Caddyfile gets the block from `fork/server/Caddyfile` with the real name written out, and it is
   reloaded. None of that interrupts the other service. Caddy then fetches the new name's
   certificate by itself. The same two changes go into the other project's own files so that they
   last.
7. **The firewall.** Nothing to change: 80 and 443 are already open for the other service.
8. **Check.** `curl https://<the name>/healthz` answers `ok`.
9. **The phone.** Paste the address and the token into the app, and press Back up now.

Updating later is `git pull` and `docker compose up -d --build`. Removing it is `docker compose down`
and deleting the folder.

### A second copy

One server is one copy. Until something better is chosen, the morning pull to the Mac goes on as the
second. The lasting choices, all the owner's: Hetzner's own server backups (20% on the server's
price), or copying `data/log/` to a Storage Box, or pulling an export to the Mac each week.

## What it costs

- **Traffic:** nothing. Hetzner does not charge for incoming traffic, and reads are rare.
- **Disk:** about 15 GB a year: the copy grows by about 35 MB a day (the phone's 28, plus the table
  the phone caps and the server does not), and the log by about 6 MB a day. The server has 28 GB free
  of 38, so `data/` goes on its own disk and costs nothing for well over a year. `status` warns once
  less than 10 GB is free; a volume then is a few cents per GB a month.
- **Load:** one run a day, a few seconds of work.

## The app's side, as built

Under `fork/app/backup/`, written on 2026-10-10. The server's own tests have a client that does the
same steps in Go (`fork/server/backup/client.go`).

| File | What it holds |
|---|---|
| `BackupSql.kt` | The two things the backup needs of a database: read rows, and write a delta file. The core's database is given to the backup as a reader only, so nothing here can write to it. |
| `BackupChecksum.kt` | The hour checksum, the same rule as the server's. |
| `BackupDelta.kt` | The schema as the server will run it, and what goes into a delta file. |
| `BackupRun.kt` | One run: ask, send, ask again. |
| `BackupServer.kt` | The HTTP client, and the check of the address the owner types. **The only file in the app that opens a connection.** |
| `BackupSqlAndroid.kt` | The two Android pieces: reading through Room's own connection, and the delta file. |
| `ServerBackup.kt` | Where it is set up, when it runs, what the screen shows. The daily job. |
| `ServerBackupSection.kt`, `BackupWords.kt` | The Strap tab's section and its sentences. |

How it behaves:

- **Off until set up.** On the Strap tab, under Server backup: Set up, then the server's address and
  the token. Until then nothing uses the network.
- **Once a day while charging on Wi-Fi**, by Android's own scheduler (WorkManager), and when the owner
  presses **Back up now**, which only needs a connection. If the phone is charging on Wi-Fi at the
  moment the server is saved, the first run starts at once.
- **A run looks at the last 15 days**, and once a week at every hour the phone holds. The first run
  looks at everything.
- **It reads the core's database through Room's own connection**, an hour of one table at a time, so
  no query holds the connection for long and none competes with the strap's writes.
- **HTTPS only**, twice over: the address is refused unless it starts with `https://`, and Android is
  told to refuse plain HTTP for the whole app. A debug build alone may reach `10.0.2.2` over plain
  HTTP, which inside an emulator is the Mac, where the server under test runs.
- **The token** is kept in a preferences file of its own, so it is never part of an exported backup's
  settings, and it is drawn as dots. Saving another server or token forgets what the last one was
  sent, so the next run looks at everything.
- **The Strap tab says** `complete 07:12`, `running`, `nothing sent yet` or `not set up`, and under it
  the last run's problem if it had one ("The server did not accept the token.", "The server could
  not be reached."), or that no backup has completed for over two days.
- **Nothing new is sent to the strap.** No Bluetooth code was touched.

What else changed in the app:

- The manifest overlays no longer remove `INTERNET`. The network policy left over from the original
  app, which allowed plain HTTP, is replaced.
- `NetworkUseTest` fails the build if any source file but `BackupServer.kt` opens a connection or
  brings in a network library.
- `FoundationDriver` puts the daily job on the schedule at each start, when a server is set up.
- **Step 0:** the Import button asks before restoring a database over 2 GiB, and a yes restores it.

## Order of work, and what proves each step

| Step | What | Done when | State on 2026-10-10 |
|---|---|---|---|
| 0 | Import restores a backup over 2 GiB when the owner confirms. | An emulator restores one. | **Done.** In an emulator a made-up backup with a 2.36 GiB database was chosen: the app asked, Cancel left everything as it was, and Restore anyway restored it in about a minute. The app opened on the restored data with no crash. Not on the phone. |
| 1 | The server, on the Mac: package, tests, image. | Its tests pass, and a private backup split into deltas comes back out of the running container identical row for row. | **Done**, in Go (`fork/server/`). Its 62 tests pass. The real backup of 2026-10-09 went up over HTTP and came back identical, both with the server run directly and inside its container. One part was not run: the Dockerfile's first stage, which compiles the binary inside Go's own image. The binary was compiled on the Mac instead and put in the same empty image. |
| 2 | The app's side, in the demo build against the server on the Mac. | In an emulator: first run, daily run, a repeat that sends nothing, a run after Import backup, no run on mobile data. Then an export from the server restores into an empty emulator, identical row for row. | **Done in an emulator, with two things read but not tried:** see below. Run once against the first, Python server and again against the Go one. Not on the phone. |
| 3 | The server on Hetzner. The owner's steps above, with a throwaway data folder first. | `healthz` answers over HTTPS and the emulator's made-up data reaches it. Then the folder is emptied. | Waits for the domain, and for this code to be pushed so the server can clone it. |
| 4 | The build on the phone, when the owner asks, after an export and with the last APK kept. | The first run finishes and the Strap tab says the server has everything. The strap checks pass. | Not started. |
| 5 | Two weeks side by side with the morning pull. | Fourteen mornings on which the server's export and the phone's own export hold the same rows. Only then is the server trusted as the backup. | Not started. |

### What steps 0 and 2 showed

On 2026-10-10, with the demo build (made-up nights, no strap) in an emulator on the phone's Android
version, and the server on the Mac. The list below was gone through against the first, Python server.
After the rewrite the first run, the comparison, the way back and the run after it were done again
against the Go server, with the same results.

- **First run.** Saving the server's address and a test token started a run at once. The server made
  its copy from the app's real schema and merged ten files. Pulled off the emulator and compared, **the
  app's database and the server's copy were identical in all 36 tables.** The server's own `verify`
  passed.
- **A repeat sent nothing** but the small tables.
- **The way back.** The server exported its last three days; the app imported that file; the app's
  database was then identical to the exported one in every table, and the app reopened on it. The
  server backup's own settings survived the import.
- **A run after that import** wanted nothing, and the server still held every row it had before.
- **A wrong token** showed "The server did not accept the token." **A stopped server** showed "The
  server could not be reached.", and once it was back the next press ran at once.
- **The daily job** was on Android's schedule needing an unmetered network and charging, read back
  from Android itself. Its first run is the one that made the first backup above.
- **The token** was in a preferences file of its own and shown as dots.

Read but not tried: that the daily job **does not run on mobile data** (the condition is set and
Android reports it; the emulator was never put on a metered network), and a **later** daily run
(Android would not run it ahead of its time when asked to). Not tried at all: the release build,
which is HTTPS only and so cannot reach a server on the Mac; a real strap's data arriving between
runs, which the tests cover and the emulator cannot; and anything on the phone.

The app's tests also build the server and run the app's backup code against it
(`ServerRoundTripTest`): a first run, a repeat, new hours, a flag changed in place, rows the phone
dropped, a run over the last days only, a wrong token, the server's `verify` and `rebuild` on what
the app sent, and a backup the server wrote unpacked by the same code the Import button runs.

### What step 1 showed

On the Mac, with the backup pulled on 2026-10-09 (194 MB) sent by the server's own client
(`fork/server/backup/client.go`), which does what the app does:

- **Up:** 1,613 hours in 14 files, 30 MB in all, 13 seconds.
- **The copy against the source:** every one of the 36 tables identical row for row, and the same
  schema, schema version, page size and vacuum mode.
- **A second run:** nothing wanted.
- **Back:** a 50 MB backup file whose database is identical to the source again.
- **The log alone** rebuilt the copy exactly, from 16 uploads.
- The real schema, 36 tables and 13 indexes, passed the rule that a schema may only create tables and
  indexes.

**In its container**, on the Mac, with the binary in an empty image and the repository's own
`compose.yaml`:

- The image is 14 MB. The service ran as its own user, with a read-only file system and no
  capabilities, and used 12 MB of memory after taking the whole backup.
- Docker's health check, as `compose.yaml` declares it, turned healthy.
- The real backup went in over HTTP, `verify` passed inside the container, and the export that came
  out was identical to the source in all 36 tables.
- The service published no port and was reached by its name on the `proxy` network from another
  container.
- **Through Caddy over HTTPS**, with the repository's own `Caddyfile` block and a local certificate:
  the health check answered, a request with no token got 401, and a body over the limit got 413.

Everything made for these runs was removed afterwards.

After step 5 the question in [11-old-raw-data.md](11-old-raw-data.md) gets easier: trimming old rows
on the phone loses nothing once the server holds them.

## How the built server differs from the first draft of this plan

- **Go, with one dependency.** The draft said Python with two libraries. The first build was Python
  with none; the owner then asked for Go, which has no SQLite of its own and so needs one library.
- **The service joins the Caddy already on the server**, over a network of their own, instead of
  bringing a second one. A second compose file still adds a Caddy for a server that has none.
- **`push`**, a command that sends a database to a server as the app would. It is the phone in the
  server's tests, and it can fill a new server from a backup on the Mac.
- **`rebuild`**, which makes a second copy from the log and compares it with the live one.
- **A virtual table in an upload is refused**, like a view or a trigger: reading one runs code of its
  own.

## What it will not do

- **The newest hours are not covered until the next run.** With one run a day, up to a day.
- **It does not restore by itself.** Getting data back takes the Mac and SSH.
- **It does not back up the app's own small files** (the worked-out sleeps, the widget's numbers).
  They are rebuilt from the rows after a restore. If Progress ever needs more than the 46 days of
  nights the app rebuilds, the sleeps file joins the upload.
- **It does not follow a schema change.** The server refuses until someone migrates the copy by hand.
- **The weekly look at everything has to finish inside the ten minutes Android gives a job.** It will
  for a year or more at today's growth. Before the database is that old, the full look should be
  split across runs. If it ever cannot finish, the Strap tab says no backup has completed.

## What is known about the server, 2026-10-10

From the owner, and from two commands he ran on it and pasted (`ss -tlnp`, `docker ps`):

- **Docker is there, and so is a reverse proxy.** The owner's other service runs as three containers:
  its API, which publishes no port; a Postgres bound to the server's loopback only; and a **Caddy
  holding ports 80 and 443**. He had thought there was no proxy. Nothing else listens but SSH.
- **No domain yet.** He will register one at Cloudflare.
- **28 GB free of 38**, on the server's own disk.
- **Only he logs in**, with a key in his password manager.
- **He chose Go for the server**, the language he maintains.

## Open, for the owner

1. Keep all of `v18AuxSample` on the server (proposed; about 2.4 GB a year more), or cap it at a week
   as the phone does?
2. One run a day while charging on Wi-Fi, plus the button (proposed), or more often?
