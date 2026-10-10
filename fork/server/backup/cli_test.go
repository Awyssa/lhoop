package backup

// The commands the owner runs: looking, reading data out as a backup, and checking the copy.

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"zombiezen.com/go/sqlite"
)

// cli runs a command in this process. It returns the exit code and what it printed to standard output.
func (c *serverCase) cli(args ...string) (int, string) {
	var stdout, stderr bytes.Buffer
	code := Main(append([]string{"--data", c.store.Dir}, args...), &stdout, &stderr)
	return code, stdout.String()
}

// unpack checks the backup has the entries the app's own export has, and returns the database's path.
func unpack(t *testing.T, backup []byte, dir string) (string, []string, map[string][]byte) {
	t.Helper()
	archive, err := zip.NewReader(bytes.NewReader(backup), int64(len(backup)))
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	contents := map[string][]byte{}
	for _, entry := range archive.File {
		names = append(names, entry.Name)
		reader, err := entry.Open()
		if err != nil {
			t.Fatal(err)
		}
		data, err := io.ReadAll(reader)
		reader.Close()
		if err != nil {
			t.Fatalf("entry %s is not sound: %v", entry.Name, err)
		}
		contents[entry.Name] = data
	}
	equal(t, "lhoop-backup.sqlite", names[0])
	equal(t, "manifest.json", names[len(names)-1])
	os.MkdirAll(dir, 0o700)
	database := filepath.Join(dir, "lhoop-backup.sqlite")
	if err := os.WriteFile(database, contents["lhoop-backup.sqlite"], 0o600); err != nil {
		t.Fatal(err)
	}
	return database, names, contents
}

// export runs the export to a file and returns the file's bytes.
func (c *serverCase) export(args ...string) []byte {
	c.t.Helper()
	entries, _ := os.ReadDir(c.dir)
	path := filepath.Join(c.dir, "out-"+strings.Repeat("x", len(entries))+".lhoopbak")
	code, _ := c.cli(append([]string{"export", "--out", path}, args...)...)
	equal(c.t, 0, code)
	data, err := os.ReadFile(path)
	if err != nil {
		c.t.Fatal(err)
	}
	return data
}

func openRO(t *testing.T, path string) *sqlite.Conn {
	t.Helper()
	conn, err := sqlite.OpenConn(path, sqlite.OpenReadOnly)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close() })
	return conn
}

func intOf(t *testing.T, conn *sqlite.Conn, query string) int64 {
	t.Helper()
	n, _, err := oneInt(conn, query)
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func differ(t *testing.T, a, b string) []string {
	t.Helper()
	found, err := DifferingTables(a, b)
	if err != nil {
		t.Fatal(err)
	}
	return found
}

func TestANewTokenIsPrintedWithTheLineThatStoresOnlyItsHash(t *testing.T) {
	c := newCase(t)
	code, out := c.cli("token", "new")
	equal(t, 0, code)
	var lines []string
	for _, line := range strings.Split(out, "\n") {
		if strings.TrimSpace(line) != "" {
			lines = append(lines, strings.TrimSpace(line))
		}
	}
	token := lines[1]
	isTrue(t, len(token) >= 40, "the token is long")
	equal(t, "LHOOP_TOKEN_SHA256="+TokenHash(token), lines[len(lines)-1])
	_, again := c.cli("token", "new")
	isTrue(t, !strings.Contains(again, token), "each token is new")
}

func TestStatusBeforeAnythingWasSent(t *testing.T) {
	c := newCase(t)
	code, out := c.cli("status")
	equal(t, 0, code)
	equal(t, "no copy yet: nothing has been sent\n", out)
}

func TestStatusCountsAndNamesButShowsNoRow(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 2*hour)
	c.sync()
	code, out := c.cli("status")
	equal(t, 0, code)
	for _, want := range []string{
		"schema version 41, 9 tables",
		"uploads     2 merged, 0 refused",
		"hours held  10, from 2030-03-17 18:00 UTC to 2030-03-17 19:00 UTC",
		"GB free",
	} {
		isTrue(t, strings.Contains(out, want), "status lacks: "+want+"\n"+out)
	}
	isTrue(t, regexp.MustCompile(`hrSample\s+7,200 rows`).MatchString(out), out)
}

func TestAWholeExportIsThePhoneAgain(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, hour+200)
	c.phone.run("INSERT INTO dailyMetric VALUES ('TESTSTRP', '2030-03-01', 50, 61.5)")
	c.syncWith(SyncOptions{AppBuild: "550", AppVersion: "made-up", SettingsJSON: `{"profile.age":40}`})
	database, names, contents := unpack(t, c.export(), filepath.Join(c.dir, "whole"))
	equal(t, []string{}, differ(t, c.phone.path, database))
	conn := openRO(t, database)
	equal(t, int64(41), intOf(t, conn, "PRAGMA user_version"))
	verdict, _, _ := oneText(conn, "PRAGMA integrity_check")
	equal(t, "ok", verdict)
	identity, _, _ := oneText(conn, "SELECT identity_hash FROM room_master_table WHERE id = 42")
	equal(t, testIdentity, identity)
	equal(t, []string{"lhoop-backup.sqlite", "settings.json", "manifest.json"}, names)
	equal(t, `{"profile.age":40}`, string(contents["settings.json"]))
	var manifest map[string]any
	if err := json.Unmarshal(contents["manifest.json"], &manifest); err != nil {
		t.Fatal(err)
	}
	equal(t, 5, len(manifest))
	equal(t, "550", manifest["appBuild"])
	equal(t, "made-up", manifest["appVersion"])
	equal(t, "android", manifest["platform"])
	equal(t, float64(41), manifest["schemaVersion"])
	isTrue(t, manifest["exportedAt"].(float64) > 1e12, "exportedAt is in milliseconds")
	// The app's own manifest has its keys in order; so must this one.
	isTrue(t, strings.HasPrefix(string(contents["manifest.json"]), `{"appBuild":"550","appVersion":"made-up","exportedAt":`), string(contents["manifest.json"]))
}

func TestAnExportOfTheLastDaysKeepsThoseDaysAndTheSmallTables(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 600)
	c.phone.wear(t0+10*day, 600)
	c.phone.run("INSERT INTO dailyMetric VALUES ('TESTSTRP', '2030-03-01', 50, 61.5)")
	c.sync()
	database, _, _ := unpack(t, c.export("--days", "3"), filepath.Join(c.dir, "days"))
	conn := openRO(t, database)
	equal(t, int64(600), intOf(t, conn, "SELECT count(*) FROM hrSample"))
	equal(t, int64(t0+10*day), intOf(t, conn, "SELECT min(ts) FROM hrSample"))
	equal(t, int64(600), intOf(t, conn, "SELECT count(*) FROM gravitySample"))
	equal(t, int64(1), intOf(t, conn, "SELECT count(*) FROM dailyMetric"))
	equal(t, int64(41), intOf(t, conn, "PRAGMA user_version"))
	equal(t, int64(1), intOf(t, conn, "PRAGMA auto_vacuum"))
	equal(t, int64(1), intOf(t, conn, "SELECT count(*) FROM sqlite_master WHERE name = 'index_event_kind'"))
	verdict, _, _ := oneText(conn, "PRAGMA integrity_check")
	equal(t, "ok", verdict)
	// Without --days the same server gives everything back.
	whole, _, _ := unpack(t, c.export(), filepath.Join(c.dir, "whole"))
	equal(t, int64(1200), intOf(t, openRO(t, whole), "SELECT count(*) FROM hrSample"))
}

func TestAnExportForThePhoneGetsThePhonesCapsBack(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 900)
	c.sync()
	before := caps["v18AuxSample"]
	caps["v18AuxSample"] = 100
	defer func() { caps["v18AuxSample"] = before }()
	database, _, _ := unpack(t, c.export("--days", "30"), filepath.Join(c.dir, "capped"))
	conn := openRO(t, database)
	equal(t, int64(100), intOf(t, conn, "SELECT count(*) FROM v18AuxSample"))
	equal(t, int64(t0+800), intOf(t, conn, "SELECT min(ts) FROM v18AuxSample"))
	equal(t, int64(900), intOf(t, conn, "SELECT count(*) FROM hrSample"))
	equal(t, int64(900), c.count("v18AuxSample", "")) // the copy itself keeps every row
}

func TestAnExportToAPipeIsASoundBackup(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 300)
	c.sync()
	code, piped := c.cli("export", "--days", "1") // standard output here is a buffer, which cannot seek either
	equal(t, 0, code)
	database, _, _ := unpack(t, []byte(piped), filepath.Join(c.dir, "piped"))
	equal(t, []string{}, differ(t, c.phone.path, database))
	ledger := c.ledger()
	days, _, _ := oneInt(ledger.conn, "SELECT days FROM export")
	size, _, _ := oneInt(ledger.conn, "SELECT bytes FROM export")
	equal(t, int64(1), days)
	equal(t, int64(len(piped)), size)
}

func TestAnExportLeavesNothingBehindAndIsCounted(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 60)
	c.sync()
	c.export("--days", "2")
	c.export()
	equal(t, []string{}, namesIn(t, c.store.TmpDir))
	_, out := c.cli("status")
	isTrue(t, strings.Contains(out, "read out    2 times"), out)
}

func TestExportWithNoCopyFailsPlainly(t *testing.T) {
	c := newCase(t)
	code, _ := c.cli("export", "--out", filepath.Join(c.dir, "none.lhoopbak"))
	equal(t, 1, code)
}

func TestVerifyPassesOnACopyThatWasOnlyEverMergedInto(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 3*hour)
	c.sync()
	c.phone.run("DELETE FROM v18AuxSample WHERE ts < ?", t0+hour+1800)
	c.sync()
	code, out := c.cli("verify")
	equal(t, 0, code)
	isTrue(t, strings.Contains(out, "1 hours hold more than the phone last had"), out)
	isTrue(t, strings.HasSuffix(out, "verified\n"), out)
}

func (c *serverCase) meddle(statement string, args ...any) {
	c.t.Helper()
	conn, err := sqlite.OpenConn(c.store.ReplicaPath, sqlite.OpenReadWrite)
	if err != nil {
		c.t.Fatal(err)
	}
	defer conn.Close()
	if err := exec(conn, statement, args...); err != nil {
		c.t.Fatal(err)
	}
}

func TestVerifyNoticesACopyChangedBehindTheLedgersBack(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, hour)
	c.sync()
	c.meddle("UPDATE hrSample SET bpm = bpm + 1 WHERE ts = ?", t0+5)
	code, out := c.cli("verify")
	equal(t, 1, code)
	isTrue(t, strings.Contains(out, "PROBLEM: hrSample: an hour in the copy no longer matches what was merged"), out)
}

func TestVerifyNoticesAnHourGoneAndAnUploadMissingFromTheLog(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 2*hour)
	c.sync()
	c.meddle("DELETE FROM gravitySample WHERE ts >= ?", t0+hour)
	logged := namesIn(t, c.store.LogDir)
	os.Remove(filepath.Join(c.store.LogDir, logged[len(logged)-1]))
	code, out := c.cli("verify")
	equal(t, 1, code)
	isTrue(t, strings.Contains(out, "gravitySample: an hour that was merged is gone from the copy"), out)
	isTrue(t, strings.Contains(out, "is missing from the log"), out)
}

func TestTheLogAloneRebuildsTheCopy(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, hour)
	c.phone.run("INSERT INTO dailyMetric VALUES ('TESTSTRP', '2030-03-01', 50, 61.5)")
	c.sync()
	c.phone.wear(t0+hour, 900)
	c.phone.run("UPDATE rrInterval SET tsSuspect = 1 WHERE ts = ?", t0+7)
	c.phone.run("DELETE FROM dailyMetric")
	c.sync()
	code, out := c.cli("rebuild", "--to", filepath.Join(c.dir, "second"))
	equal(t, 0, code)
	isTrue(t, strings.Contains(out, "replayed 3 uploads"), out)
	isTrue(t, strings.Contains(out, "the log rebuilds the copy exactly"), out)
}

func TestARebuildSaysWhenTheLiveCopyIsNotWhatTheLogGives(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 600)
	c.sync()
	c.meddle("DELETE FROM event")
	code, out := c.cli("rebuild", "--to", filepath.Join(c.dir, "second"))
	equal(t, 1, code)
	isTrue(t, strings.Contains(out, "DIFFERENT from the live copy in: event"), out)
}

func TestPushSendsADatabaseAsTheAppWould(t *testing.T) {
	c := newCase(t)
	c.phone.wear(t0, 300)
	t.Setenv("LHOOP_TOKEN", testToken)
	code, out := c.cli("push", c.phone.path, c.url)
	equal(t, 0, code)
	isTrue(t, strings.Contains(out, "the server has everything"), out)
	c.assertCopyIsThePhone()
}

func TestPushWithoutATokenSaysSo(t *testing.T) {
	c := newCase(t)
	t.Setenv("LHOOP_TOKEN", "")
	code, _ := c.cli("push", c.phone.path, c.url)
	equal(t, 1, code)
}

func TestACommandThatDoesNotExistPrintsTheUsage(t *testing.T) {
	var stdout, stderr bytes.Buffer
	equal(t, 2, Main([]string{"nothing"}, &stdout, &stderr))
	isTrue(t, strings.Contains(stderr.String(), "lhoop-backup keeps a copy"), stderr.String())
	equal(t, 2, Main(nil, &stdout, &stderr))
}
