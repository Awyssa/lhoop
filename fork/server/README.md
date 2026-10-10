# The backup server

A small service that keeps a copy of the LHOOP app's database on a server the owner runs. The why and
the design are in [`fork/docs/12-server-backup.md`](../docs/12-server-backup.md). This page is how to
run it.

It is written in Go and builds to one static binary. In production that binary is the only thing in
its Docker image: no shell, no interpreter, no operating system. It runs behind a reverse proxy, which
does the HTTPS: the Caddy already on the server, or one this folder adds.

It has one dependency, the SQLite library `zombiezen.com/go/sqlite`, which is SQLite itself translated
to Go (`modernc.org/sqlite`) with a thin layer over it. That brings eleven more modules with it. All
twelve are pinned by hash in `go.sum`.

## What is here

| Path | What it is |
|---|---|
| `main.go` | The entry point. |
| `backup/checksum.go` | The hour checksum, the rule both sides use to tell they hold the same rows. |
| `backup/replica.go` | The copy: creating it from the phone's schema, checking and merging an upload, writing it out as a backup. |
| `backup/ledger.go` | The server's own records. No health data. |
| `backup/store.go` | The folder on disk and the three things a request can do to it. |
| `backup/server.go` | The four HTTP routes. None returns data. |
| `backup/cli.go` | The commands: `serve`, and the ones for looking and for reading data out. |
| `backup/client.go` | What the app does, in Go: the phone in the tests, and `push`. |
| `backup/sql.go` | Opening a database so that every file is treated as data. |
| `testdata/checksum_cases.json` | The checksum rule as made-up rows and expected results. The app's tests read this file too. |
| `Dockerfile`, `compose.yaml`, `.env.example`, `.dockerignore` | The deployment: the service alone, with no port published. |
| `Caddyfile`, `compose.caddy.yaml` | The site block for a reverse proxy, and a Caddy of its own for a server that has none. |

## Tests

```bash
cd fork/server
go test ./...
```

Go 1.24 or newer. Everything in the tests is made up, except one test that is skipped unless an
unpacked backup is under `whoop-data/lhoop-backups/`: it sends that backup to a server on this
machine, reads it back and compares every row. Run it with `-v` to see its sizes and timings; it
prints no figure from the data.

The app has a test of its own, `ServerRoundTripTest`, that builds this server and runs the app's
Kotlin against it, including unpacking a backup this server wrote with the app's own import code.

## Running it on this machine

```bash
cd fork/server
go build -o lhoop-backup .
./lhoop-backup token new                   # prints a token and the line holding its hash
LHOOP_TOKEN_SHA256=<the hash> ./lhoop-backup --data ./data serve --bind 127.0.0.1
```

Then, from another terminal, send it a database the way the app would:

```bash
LHOOP_TOKEN=<the token> ./lhoop-backup push <path to a database> http://127.0.0.1:8787
./lhoop-backup --data ./data status
```

`./data` and the binary are in `.gitignore`. Delete them when done.

## Putting it on the server

The owner runs these. Nothing here needs the token or the host name to be in git.

**A name.** A DNS record for a subdomain pointing at the server. At Cloudflare, set it to **DNS
only**. Proxied, Cloudflare would decrypt every upload on its way through.

**The service.** It publishes no port. The reverse proxy reaches it over a Docker network called
`proxy`, which is made once.

```bash
git clone https://github.com/Awyssa/lhoop.git
cd lhoop/fork/server
cp .env.example .env
mkdir -p data && sudo chown 10001:10001 data && sudo chmod 700 data
docker network create proxy
docker compose build api
docker compose run --rm api token new
```

The build fetches Go's own image once to compile with; what runs afterwards is about 14 MB. The last
command prints a token and a line starting `LHOOP_TOKEN_SHA256=`. Put that line in `.env`. Keep the
token for the phone: it is not stored anywhere on the server and cannot be shown again.

```bash
docker compose up -d
docker compose ps          # api turns "healthy" within half a minute
```

**The reverse proxy.** See whether the server has one:

```bash
docker ps --format '{{.Names}}\t{{.Ports}}'
```

*A Caddy container already holds ports 80 and 443* (the owner's server). It fronts this service too,
and it can be given the new site without being restarted. `<caddy>` below is that container's name.

1. Join it to the `proxy` network, live:

   ```bash
   docker network connect proxy <caddy>
   ```

2. Add the block from this folder's `Caddyfile` to that Caddy's own Caddyfile, with the real name
   written out in place of `{$LHOOP_HOST}`. Write the name out: a variable that Caddy was never given
   would leave the site with no name and the whole file invalid. Do this only once the DNS record
   exists, or Caddy will ask for a certificate it cannot get.

3. Check the file as the container sees it, then load it. A reload does not interrupt the sites
   already served, and Caddy keeps its old configuration if the new one is refused.

   ```bash
   docker exec <caddy> caddy validate --config /etc/caddy/Caddyfile
   docker exec <caddy> caddy reload --config /etc/caddy/Caddyfile
   ```

   If `validate` does not show the new site, the container is still reading the old file: a
   Caddyfile mounted as a single file stops following it when an editor replaces the file instead
   of writing into it. Recreating the container picks it up, at the cost of a few seconds.

4. Make both changes last. In that Caddy's own project, the file the Caddyfile comes from keeps the
   new block, and its compose file has the service join `proxy` as well as its own network:

   ```yaml
   services:
     caddy:
       networks: [default, proxy]

   networks:
     proxy:
       external: true
   ```

   Without this, the next time that container is recreated it comes back without the network, and
   the new site answers 502 until step 1 is run again. If that project is deployed from a
   repository, the changes belong in the repository, or the next deploy undoes them.

*Nothing holds 80 and 443.* Put the name on the `LHOOP_HOST=` line of `.env`, open both ports in the
firewall, and start with this folder's own Caddy:

```bash
docker compose -f compose.yaml -f compose.caddy.yaml up -d
```

**Check.** Either way, Caddy fetches the certificate for the new name within a few seconds.

```bash
curl https://<the name>/healthz        # answers: ok
```

## Day to day

Run on the server, in `lhoop/fork/server`. The image has no shell, so a command is always the binary
by its full path.

```bash
docker compose exec api /lhoop-backup status     # what the copy holds, last upload, free disk
docker compose exec api /lhoop-backup verify     # both files sound, the log whole, the copy as merged
docker compose logs --tail 50 api                # one line per request: time, route, status, size
```

**Reading data out**, from the Mac. The result is a backup the app's Import backup restores.

```bash
ssh <the server> 'cd lhoop/fork/server && docker compose exec -T api /lhoop-backup export --days 60' > lhoop.lhoopbak
```

Leave `--days` off for everything the server holds. For a restore to the phone keep `--days`: sixty
days stays under 2 GiB, and a larger backup makes the app ask before it restores.

**Checking the log can rebuild the copy.** Slow, and it needs free disk the size of the copy. The
second copy is cleared the next time the server starts.

```bash
docker compose exec api /lhoop-backup rebuild --to /data/tmp/second
```

**A new token.** Run `token new` again, replace the line in `.env`, then `docker compose up -d`. The
old token stops working at once.

**Updating.** `git pull`, then `docker compose up -d --build`.

**Removing it.** `docker compose down`, then delete the folder. The copy goes with it.

## What is on disk

```
data/
  replica.sqlite    the copy: the phone's own tables
  ledger.sqlite     what arrived and when
  log/              every accepted upload, as received
  incoming/, tmp/   work in progress, cleared when the server starts
.env                the token's hash
caddy/              only with compose.caddy.yaml: that Caddy's certificate
```

`data/` is health data. It is readable only by the container's user, and `.dockerignore` keeps it out
of the image build. `data/log/` and `data/ledger.sqlite` are what to copy elsewhere for a second
copy: the log only ever grows by new files, and `rebuild` turns it back into the copy.

## Limits

- One upload at a time. A second waits for the first.
- An upload is at most 64 MiB as sent and 512 MiB unpacked. The app sends a day of hours to a file,
  about 6 MB.
- While an export with `--days` runs, an upload that arrives may be turned away. The app sends it
  again on its next run.
- The server refuses uploads from a phone whose schema is not the copy's. The app's schema is fixed by
  the project's rules, so this is a tripwire, not something expected.
