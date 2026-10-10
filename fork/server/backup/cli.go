package backup

// The commands: `serve` for the container, and the ones the owner runs over SSH to look and to read
// data out.

import (
	"compress/gzip"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"zombiezen.com/go/sqlite"
)

func utc(seconds int64) string {
	return time.Unix(seconds, 0).UTC().Format("2006-01-02 15:04 UTC")
}

// grouped writes a count with a comma between the thousands.
func grouped(n int64) string {
	digits := strconv.FormatInt(n, 10)
	var out []byte
	for i := 0; i < len(digits); i++ {
		if i > 0 && (len(digits)-i)%3 == 0 && digits[i-1] != '-' {
			out = append(out, ',')
		}
		out = append(out, digits[i])
	}
	return string(out)
}

// tableDigest is the row count of a table and a SHA-256 over every row in primary-key order. Each value
// goes in with its kind, so a whole number and a decimal of the same value are not the same row.
func tableDigest(conn *sqlite.Conn, table string) (int64, string, error) {
	cols, err := columns(conn, "main", table)
	if err != nil {
		return 0, "", err
	}
	type keyColumn struct {
		name  string
		order int64
	}
	var keys []keyColumn
	if err := run(conn, fmt.Sprintf("PRAGMA table_info(%s)", quote(table)), nil, func(row *sqlite.Stmt) error {
		if row.ColumnInt64(5) > 0 {
			keys = append(keys, keyColumn{row.ColumnText(1), row.ColumnInt64(5)})
		}
		return nil
	}); err != nil {
		return 0, "", err
	}
	sort.Slice(keys, func(i, j int) bool { return keys[i].order < keys[j].order })
	order := "rowid"
	if len(keys) > 0 {
		names := make([]string, len(keys))
		for i, k := range keys {
			names[i] = quote(k.name)
		}
		order = strings.Join(names, ", ")
	}
	digest := sha256.New()
	var count int64
	var number [9]byte
	var scratch []byte
	err = run(conn, fmt.Sprintf("SELECT %s FROM %s ORDER BY %s", columnList(cols), quote(table), order), nil, func(row *sqlite.Stmt) error {
		for i := 0; i < row.ColumnCount(); i++ {
			switch row.ColumnType(i) {
			case sqlite.TypeNull:
				digest.Write([]byte{'n'})
			case sqlite.TypeInteger:
				number[0] = 'i'
				binary.BigEndian.PutUint64(number[1:], uint64(row.ColumnInt64(i)))
				digest.Write(number[:])
			case sqlite.TypeFloat:
				number[0] = 'f'
				binary.BigEndian.PutUint64(number[1:], math.Float64bits(row.ColumnFloat(i)))
				digest.Write(number[:])
			default:
				kind := byte('t')
				if row.ColumnType(i) == sqlite.TypeBlob {
					kind = 'b'
				}
				size := row.ColumnLen(i)
				if cap(scratch) < size {
					scratch = make([]byte, size)
				}
				n := row.ColumnBytes(i, scratch[:size])
				number[0] = kind
				binary.BigEndian.PutUint64(number[1:], uint64(n))
				digest.Write(number[:])
				digest.Write(scratch[:n])
			}
		}
		count++
		return nil
	})
	return count, hex.EncodeToString(digest.Sum(nil)), err
}

// DifferingTables lists the tables whose rows are not the same in the two databases. None means they
// hold the same data.
func DifferingTables(pathA, pathB string) ([]string, error) {
	a, err := connect(pathA, "ro")
	if err != nil {
		return nil, err
	}
	defer a.Close()
	b, err := connect(pathB, "ro")
	if err != nil {
		return nil, err
	}
	defer b.Close()
	shapeA, err := shapeOf(a, "main")
	if err != nil {
		return nil, err
	}
	shapeB, err := shapeOf(b, "main")
	if err != nil {
		return nil, err
	}
	differ := []string{}
	for _, table := range shapeA.names {
		if _, ok := shapeB.Tables[table]; !ok {
			differ = append(differ, table)
			continue
		}
		countA, sumA, err := tableDigest(a, table)
		if err != nil {
			return nil, err
		}
		countB, sumB, err := tableDigest(b, table)
		if err != nil {
			return nil, err
		}
		if countA != countB || sumA != sumB {
			differ = append(differ, table)
		}
	}
	for _, table := range shapeB.names {
		if _, ok := shapeA.Tables[table]; !ok {
			differ = append(differ, table)
		}
	}
	sort.Strings(differ)
	return differ, nil
}

func status(store *Store, out io.Writer) int {
	ledger, err := openLedger(store.LedgerPath)
	if err != nil {
		fmt.Fprintln(out, "the ledger could not be opened:", err)
		return 1
	}
	info, err := ledger.summary()
	version, _ := ledger.get("user_version")
	ledger.Close()
	if err != nil {
		fmt.Fprintln(out, "the ledger could not be read:", err)
		return 1
	}
	if !exists(store.ReplicaPath) {
		fmt.Fprintln(out, "no copy yet: nothing has been sent")
		return 0
	}
	file, _ := os.Stat(store.ReplicaPath)
	conn, err := connect(store.ReplicaPath, "ro")
	if err != nil {
		fmt.Fprintln(out, "the copy could not be opened:", err)
		return 1
	}
	defer conn.Close()
	known, err := shapeOf(conn, "main")
	if err != nil {
		fmt.Fprintln(out, "the copy could not be read:", err)
		return 1
	}
	counts := map[string]int64{}
	for _, table := range known.names {
		counts[table], _, _ = oneInt(conn, "SELECT count(*) FROM "+quote(table))
	}
	last := "never"
	if info.HasLast {
		last = utc(info.LastAt)
	}
	fmt.Fprintf(out, "copy        %.1f MB, schema version %s, %d tables\n", float64(file.Size())/1e6, version, len(known.Tables))
	fmt.Fprintf(out, "uploads     %d merged, %d refused; last %s\n", info.Merged, info.Refused, last)
	if info.Hours > 0 {
		fmt.Fprintf(out, "hours held  %d, from %s to %s\n", info.Hours, utc(info.FirstHour*hourSec), utc(info.LastHour*hourSec))
	}
	fmt.Fprintf(out, "read out    %d times\n", info.Exports)
	names := append([]string(nil), known.names...)
	sort.SliceStable(names, func(i, j int) bool { return counts[names[i]] > counts[names[j]] })
	for _, table := range names {
		if counts[table] > 0 {
			fmt.Fprintf(out, "  %-22s %12s rows\n", table, grouped(counts[table]))
		}
	}
	free, _ := freeBytes(store.Dir)
	warning := ""
	if free < lowDisk {
		warning = "  ** LOW: under 10 GB **"
	}
	fmt.Fprintf(out, "disk        %.1f GB free%s\n", float64(free)/1e9, warning)
	return 0
}

// counted is standard output with a count of what went through it.
type counted struct {
	w io.Writer
	n int64
}

func (c *counted) Write(p []byte) (int, error) {
	n, err := c.w.Write(p)
	c.n += int64(n)
	return n, err
}

// export writes the copy as a backup the app can import: all of it, or the last days days.
func export(store *Store, days *int, outPath string, stdout, stderr io.Writer) int {
	if !exists(store.ReplicaPath) {
		fmt.Fprintln(stderr, "no copy yet: nothing to export")
		return 1
	}
	fail := func(err error) int {
		fmt.Fprintln(stderr, "the export failed:", err)
		return 1
	}
	ledger, err := openLedger(store.LedgerPath)
	if err != nil {
		return fail(err)
	}
	settings, _ := ledger.get("settings_json")
	build, _ := ledger.get("app_build")
	version, _ := ledger.get("app_version")
	schemaVersion, _ := ledger.get("user_version")
	ledger.Close()
	schemaNumber, _ := strconv.ParseInt(schemaVersion, 10, 64)
	// The keys the app's own manifest has; encoding/json writes a map's keys in order, as the app does.
	manifest := map[string]any{
		"appBuild": build, "appVersion": version, "exportedAt": time.Now().UnixMilli(),
		"platform": "android", "schemaVersion": schemaNumber,
	}
	target := stdout
	if outPath != "" {
		file, err := os.Create(outPath)
		if err != nil {
			return fail(err)
		}
		defer file.Close()
		target = file
	}
	stream := &counted{w: target}
	if days == nil {
		held, err := holdStill(store.ReplicaPath) // nothing can change the file while it is being read
		if err != nil {
			return fail(err)
		}
		err = writeBackup(stream, store.ReplicaPath, settings, manifest)
		held.Close()
		if err != nil {
			return fail(err)
		}
	} else {
		trimmed := filepath.Join(store.TmpDir, fmt.Sprintf("export-%d.sqlite", os.Getpid()))
		defer os.Remove(trimmed)
		os.Remove(trimmed)
		if err := writeTrimmed(store.ReplicaPath, trimmed, *days); err != nil {
			return fail(err)
		}
		if err := writeBackup(stream, trimmed, settings, manifest); err != nil {
			return fail(err)
		}
	}
	if ledger, err := openLedger(store.LedgerPath); err == nil {
		ledger.recordExport(time.Now().Unix(), days, stream.n)
		ledger.Close()
	}
	if days == nil {
		fmt.Fprintln(stderr, "exported everything")
	} else {
		fmt.Fprintf(stderr, "exported the last %d days\n", *days)
	}
	return 0
}

// verify is the cheap checks: both files sound, every upload still in the log, and the copy against the
// checksums merged.
func verify(store *Store, out io.Writer) int {
	if !exists(store.ReplicaPath) {
		fmt.Fprintln(out, "no copy yet: nothing to verify")
		return 0
	}
	var problems []string
	failed := func(err error) int {
		fmt.Fprintln(out, "the check could not run:", err)
		return 1
	}
	conn, err := connect(store.ReplicaPath, "ro")
	if err != nil {
		return failed(err)
	}
	defer conn.Close()
	if verdict, _, err := oneText(conn, "PRAGMA integrity_check"); err != nil || verdict != "ok" {
		problems = append(problems, "the copy fails its integrity check")
	}
	known, err := shapeOf(conn, "main")
	if err != nil {
		return failed(err)
	}
	ledger, err := openLedger(store.LedgerPath)
	if err != nil {
		return failed(err)
	}
	if verdict, _, err := oneText(ledger.conn, "PRAGMA integrity_check"); err != nil || verdict != "ok" {
		problems = append(problems, "the ledger fails its integrity check")
	}
	uploads, err := ledger.mergedUploads()
	if err != nil {
		ledger.Close()
		return failed(err)
	}
	recorded, err := ledger.buckets()
	ledger.Close()
	if err != nil {
		return failed(err)
	}
	for _, upload := range uploads {
		path := filepath.Join(store.LogDir, upload.LogFile)
		if upload.LogFile == "" || !exists(path) {
			problems = append(problems, fmt.Sprintf("upload %d is missing from the log", upload.ID))
		} else if strings.HasSuffix(upload.LogFile, ".sqlite.gz") {
			if sum, err := sha256Of(path); err != nil || sum != upload.SHA256 {
				problems = append(problems, fmt.Sprintf("upload %d in the log is not the file that was received", upload.ID))
			}
		}
	}
	held := map[string]map[Bucket]string{}
	for _, table := range known.sample() {
		if held[table], err = hourPrints(conn, "main", table, known.Tables[table], nil); err != nil {
			return failed(err)
		}
	}
	var compared, more int
	for _, bucket := range recorded {
		now, ok := held[bucket.Table][Bucket{bucket.Device, bucket.Hour}]
		switch {
		case !ok:
			problems = append(problems, bucket.Table+": an hour that was merged is gone from the copy")
		case rowsIn(now) == rowsIn(bucket.Print):
			compared++
			if now != bucket.Print {
				problems = append(problems, bucket.Table+": an hour in the copy no longer matches what was merged")
			}
		default:
			more++ // the copy holds rows the phone has since dropped: expected, and not comparable
		}
	}
	fmt.Fprintf(out, "%d hours match what the phone sent; %d hours hold more than the phone last had\n", compared, more)
	for i, problem := range problems {
		if i == 20 {
			fmt.Fprintf(out, "... and %d more\n", len(problems)-20)
			break
		}
		fmt.Fprintln(out, "PROBLEM: "+problem)
	}
	if len(problems) > 0 {
		fmt.Fprintf(out, "%d problems\n", len(problems))
		return 1
	}
	fmt.Fprintln(out, "verified")
	return 0
}

// rebuild builds a second copy from the log alone, in destDir, and says whether it equals the live one.
func rebuild(store *Store, destDir string, out, stderr io.Writer) int {
	fail := func(err error) int {
		fmt.Fprintln(stderr, "the rebuild failed:", err)
		return 1
	}
	other, err := NewStore(destDir)
	if err != nil {
		return fail(err)
	}
	if exists(other.ReplicaPath) {
		fmt.Fprintln(stderr, "that folder already holds a copy")
		return 1
	}
	ledger, err := openLedger(store.LedgerPath)
	if err != nil {
		return fail(err)
	}
	uploads, err := ledger.mergedUploads()
	storedVersion, _ := ledger.get("user_version")
	identityHash, _ := ledger.get("identity_hash")
	ledger.Close()
	if err != nil {
		return fail(err)
	}
	userVersion, _ := strconv.ParseInt(storedVersion, 10, 64)
	for _, upload := range uploads {
		path := filepath.Join(store.LogDir, upload.LogFile)
		if upload.Kind == "schema" {
			body, err := readSchemaLog(path)
			if err != nil {
				return fail(err)
			}
			version, _ := wholeNumber(body.UserVersion)
			pageSize, autoVacuum := int64(4096), int64(1)
			if n, ok := wholeNumber(body.PageSize); ok {
				pageSize = n
			}
			if n, ok := wholeNumber(body.AutoVacuum); ok {
				autoVacuum = n
			}
			if _, err := createReplica(other.ReplicaPath, version, body.Statements, pageSize, autoVacuum); err != nil {
				return fail(err)
			}
			continue
		}
		if sum, err := sha256Of(path); err != nil || sum != upload.SHA256 {
			fmt.Fprintf(stderr, "upload %d in the log is not the file that was received\n", upload.ID)
			return 1
		}
		unpacked := filepath.Join(other.TmpDir, "delta.sqlite")
		if _, err := gunzipTo(path, unpacked, maxUnpacked); err != nil {
			return fail(err)
		}
		conn, err := connect(other.ReplicaPath, "rw")
		if err != nil {
			return fail(err)
		}
		_, err = merge(conn, unpacked, userVersion, identityHash)
		conn.Close()
		os.Remove(unpacked)
		if err != nil {
			return fail(err)
		}
	}
	differ, err := DifferingTables(store.ReplicaPath, other.ReplicaPath)
	if err != nil {
		return fail(err)
	}
	fmt.Fprintf(out, "replayed %d uploads\n", len(uploads))
	if len(differ) > 0 {
		fmt.Fprintln(out, "DIFFERENT from the live copy in: "+strings.Join(differ, ", "))
		return 1
	}
	fmt.Fprintln(out, "the log rebuilds the copy exactly")
	return 0
}

func readSchemaLog(path string) (schemaRequest, error) {
	var body schemaRequest
	f, err := os.Open(path)
	if err != nil {
		return body, err
	}
	defer f.Close()
	unpacker, err := gzip.NewReader(f)
	if err != nil {
		return body, err
	}
	decoder := json.NewDecoder(unpacker)
	decoder.UseNumber()
	return body, decoder.Decode(&body)
}

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

const usage = `lhoop-backup keeps a copy of the LHOOP app's database.

  lhoop-backup [--data DIR] serve [--bind ADDRESS] [--port PORT]   run the server
  lhoop-backup healthcheck                                         ask the running server whether it is up
  lhoop-backup token new                                           print a new token and the line for .env
  lhoop-backup [--data DIR] status                                 what the copy holds
  lhoop-backup [--data DIR] export [--days N] [--out FILE]         write the copy as a backup the app can import
  lhoop-backup [--data DIR] verify                                 check the copy against what was merged
  lhoop-backup [--data DIR] rebuild --to DIR                       build a second copy from the log and compare
  lhoop-backup push [--days N] DATABASE URL                        send a database as the app does (token in LHOOP_TOKEN)

DIR is the server's folder: LHOOP_DATA, or /data.
`

// Main runs one command and returns the exit code.
func Main(args []string, stdout, stderr io.Writer) int {
	global := flag.NewFlagSet("lhoop-backup", flag.ContinueOnError)
	global.SetOutput(stderr)
	global.Usage = func() { fmt.Fprint(stderr, usage) }
	dataDir := global.String("data", env("LHOOP_DATA", "/data"), "the server's folder")
	if err := global.Parse(args); err != nil {
		return 2
	}
	rest := global.Args()
	if len(rest) == 0 {
		fmt.Fprint(stderr, usage)
		return 2
	}
	command, rest := rest[0], rest[1:]
	sub := flag.NewFlagSet(command, flag.ContinueOnError)
	sub.SetOutput(stderr)

	switch command {
	case "token":
		if len(rest) != 1 || rest[0] != "new" {
			fmt.Fprint(stderr, usage)
			return 2
		}
		token, err := NewToken()
		if err != nil {
			fmt.Fprintln(stderr, err)
			return 1
		}
		fmt.Fprintf(stdout, "The token, for the app. It is shown once:\n\n  %s\n\nThe line for .env on the server:\n\n  LHOOP_TOKEN_SHA256=%s\n", token, TokenHash(token))
		return 0
	case "healthcheck":
		client := http.Client{Timeout: 5 * time.Second}
		response, err := client.Get("http://127.0.0.1:" + env("LHOOP_PORT", "8787") + "/healthz")
		if err != nil {
			return 1
		}
		response.Body.Close()
		if response.StatusCode != http.StatusOK {
			return 1
		}
		return 0
	case "serve":
		defaultPort, _ := strconv.Atoi(env("LHOOP_PORT", "8787"))
		bind := sub.String("bind", env("LHOOP_BIND", "0.0.0.0"), "the address to listen on")
		port := sub.Int("port", defaultPort, "the port to listen on")
		if err := sub.Parse(rest); err != nil {
			return 2
		}
		if err := Serve(*dataDir, os.Getenv("LHOOP_TOKEN_SHA256"), *bind, *port, stdout); err != nil {
			fmt.Fprintln(stderr, err)
			return 1
		}
		return 0
	case "push":
		days := sub.Int("days", -1, "only the last DAYS days")
		if err := sub.Parse(rest); err != nil || sub.NArg() != 2 {
			fmt.Fprint(stderr, usage)
			return 2
		}
		token := os.Getenv("LHOOP_TOKEN")
		if token == "" {
			fmt.Fprintln(stderr, "set LHOOP_TOKEN to the server's token first")
			return 1
		}
		opts := SyncOptions{AppBuild: "push"}
		if *days >= 0 {
			opts.Days = days
		}
		report, err := Sync(sub.Arg(0), sub.Arg(1), token, opts)
		if err != nil {
			fmt.Fprintln(stderr, "the push failed:", err)
			return 1
		}
		verdict := "NOT verified"
		if report.Verified {
			verdict = "the server has everything"
		}
		fmt.Fprintf(stdout, "%d hours wanted, %d files, %.1f MB sent; %s\n", report.HoursWanted, report.Deltas, float64(report.BytesSent)/1e6, verdict)
		if !report.Verified {
			return 1
		}
		return 0
	}

	if command != "status" && command != "export" && command != "verify" && command != "rebuild" {
		fmt.Fprint(stderr, usage)
		return 2
	}
	days := sub.Int("days", -1, "only the last DAYS days of the sample tables, capped as a phone caps them")
	outPath := sub.String("out", "", "write to this file instead of standard output")
	to := sub.String("to", "", "an empty folder for the second copy")
	if err := sub.Parse(rest); err != nil {
		return 2
	}
	store, err := NewStore(*dataDir)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	switch command {
	case "status":
		return status(store, stdout)
	case "export":
		var lastDays *int
		if *days >= 0 {
			lastDays = days
		}
		return export(store, lastDays, *outPath, stdout, stderr)
	case "verify":
		return verify(store, stdout)
	case "rebuild":
		if *to == "" {
			fmt.Fprint(stderr, usage)
			return 2
		}
		return rebuild(store, *to, stdout, stderr)
	}
	return 2
}
