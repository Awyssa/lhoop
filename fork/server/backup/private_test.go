package backup

// The round trip on a real backup, when there is one on this machine.
//
// It needs an unpacked backup under whoop-data/, which is private and never in git, so everywhere else
// this test is skipped. It prints sizes and timings and no figure from the data.

import (
	"os"
	"path/filepath"
	"sort"
	"testing"
	"time"

	"zombiezen.com/go/sqlite"
)

func newestPrivateBackup() string {
	if named := os.Getenv("LHOOP_PRIVATE_BACKUP"); named != "" {
		return named
	}
	found, _ := filepath.Glob("../../../whoop-data/lhoop-backups/*/lhoop-backup.sqlite")
	sort.Slice(found, func(i, j int) bool {
		a, _ := os.Stat(found[i])
		b, _ := os.Stat(found[j])
		return a.ModTime().Before(b.ModTime())
	})
	if len(found) == 0 {
		return ""
	}
	return found[len(found)-1]
}

func TestARealBackupGoesUpAndComesBackTheSame(t *testing.T) {
	source := newestPrivateBackup()
	if source == "" {
		t.Skip("no private backup on this machine")
	}
	c := newCase(t)
	started := time.Now()
	report, err := Sync(source, c.url, testToken, SyncOptions{})
	if err != nil {
		t.Fatal(err)
	}
	isTrue(t, report.SchemaCreated && report.Verified, "the first run made the copy and was confirmed")
	t.Logf("up: %d hours in %d files, %.1f MB, %.0fs", report.HoursWanted, report.Deltas, float64(report.BytesSent)/1e6, time.Since(started).Seconds())
	equal(t, []string{}, differ(t, source, c.store.ReplicaPath))

	again, err := Sync(source, c.url, testToken, SyncOptions{})
	if err != nil {
		t.Fatal(err)
	}
	equal(t, 0, again.HoursWanted)
	isTrue(t, again.Verified, "the second run was confirmed")

	whole := c.export()
	back, _, _ := unpack(t, whole, filepath.Join(c.dir, "whole"))
	equal(t, []string{}, differ(t, source, back))
	sourceInfo, _ := os.Stat(source)
	backInfo, _ := os.Stat(back)
	t.Logf("back: %.1f MB as a backup file, %.1f MB unpacked, source %.1f MB", float64(len(whole))/1e6, float64(backInfo.Size())/1e6, float64(sourceInfo.Size())/1e6)

	a, b := openRO(t, source), openRO(t, back)
	for _, pragma := range []string{"user_version", "page_size", "auto_vacuum"} {
		equal(t, intOf(t, a, "PRAGMA "+pragma), intOf(t, b, "PRAGMA "+pragma))
	}
	schema := func(conn *sqlite.Conn) []string {
		var out []string
		run(conn, "SELECT type || '|' || name || '|' || tbl_name || '|' || coalesce(sql, '') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name", nil,
			func(row *sqlite.Stmt) error { out = append(out, row.ColumnText(0)); return nil })
		return out
	}
	equal(t, schema(a), schema(b))

	code, out := c.cli("verify")
	equal(t, 0, code)
	t.Log(out)
	code, out = c.cli("rebuild", "--to", filepath.Join(c.dir, "second"))
	equal(t, 0, code)
	t.Log(out)
}
